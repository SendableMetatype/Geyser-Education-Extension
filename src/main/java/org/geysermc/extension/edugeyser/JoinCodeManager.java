package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.network.NethernetManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Education Edition join codes (multi-account).
 *
 * Architecture: Geyser owns the Nethernet server and connection ID. This
 * manager only handles Discovery API registration (join codes) and OAuth
 * device code flows. Each education tenant gets its own Discovery
 * registration pointing at Geyser's shared connection ID.
 */
public class JoinCodeManager {

    private static final String EDU_CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2";
    private static final String SCOPE = "16556bfc-5102-43c9-a82a-3ea5e4810689/.default offline_access";
    private static final String ENTRA_BASE = "https://login.microsoftonline.com/organizations/oauth2/v2.0";
    private static final String SESSION_FILE = "sessions_joincode.yml";
    private static final String CONFIG_FILE = "joincode_config.yml";
    private static final String LOG_PREFIX = "[JoinCode] ";
    private static final int HTTP_TIMEOUT = 15000;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 100;
    private static final long RESTORE_RETRY_INTERVAL_SECONDS = HEARTBEAT_INTERVAL_SECONDS;
    private static final long ACCESS_TOKEN_REFRESH_MARGIN_SECONDS = 60;
    private static final long CODE_REMINDER_INTERVAL_SECONDS = 900;

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final Object fileLock = new Object();
    private final List<JoinCodeAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<JoinCodeAccount, List<ScheduledFuture<?>>> accountTasks = new ConcurrentHashMap<>();
    private final Map<JoinCodeAccount, ScheduledFuture<?>> restoreRetryTasks = new ConcurrentHashMap<>();
    private final Map<JoinCodeAccount, ScheduledFuture<?>> deviceCodeRetryTasks = new ConcurrentHashMap<>();
    private volatile @Nullable ScheduledFuture<?> codeReminderTask;
    private volatile boolean shutdownRequested;

    // Global config shared by all accounts
    private String worldName = "Education Server";
    private String hostName = "EduGeyser";
    private int maxPlayers = 40;

    public JoinCodeManager(EduGeyserExtension extension) {
        this.extension = extension;
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    // ---- Lifecycle ----

    public void initialize() {
        try {
            Files.createDirectories(extension.dataFolder());
        } catch (IOException e) {
            extension.logger().error(LOG_PREFIX + "Failed to create data folder: " + e.getMessage());
            return;
        }
        if (!loadConfig()) {
            return;
        }
        loadAllAccounts();

        // Start the Nethernet server via Geyser's API
        if (!ensureNethernet()) {
            extension.logger().error(LOG_PREFIX + "Nethernet server failed to start. " +
                    "Connection ID and join codes will not work.");
        }

        // Restore existing accounts
        for (int i = 0; i < accounts.size(); i++) {
            final int idx = i;
            JoinCodeAccount account = accounts.get(i);
            scheduler.execute(() -> runAuthFlow(account, idx));
        }

        scheduleCodeReminder();
    }

    public void shutdown() {
        shutdownRequested = true;
        if (codeReminderTask != null) codeReminderTask.cancel(false);
        for (List<ScheduledFuture<?>> tasks : accountTasks.values()) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }
        for (ScheduledFuture<?> task : restoreRetryTasks.values()) {
            task.cancel(false);
        }
        restoreRetryTasks.clear();
        for (ScheduledFuture<?> task : deviceCodeRetryTasks.values()) {
            task.cancel(false);
        }
        deviceCodeRetryTasks.clear();

        // We deliberately do not dehost on shutdown. Leaving the registration in
        // place lets the next startup resume the same join code, since the server
        // keeps it alive far longer than any restart. The Nethernet server lifecycle
        // is owned by Geyser.

        saveAllAccounts();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    // ---- Auth Flow (per account) ----

    private void runAuthFlow(JoinCodeAccount account, int index) {
        try {
            restoreOrAuthenticate(account, index);
        } catch (InterruptedException e) {
            extension.logger().debug(LOG_PREFIX + e.getMessage());
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Auth flow failed for account #" + (index + 1) + ": " + e.getMessage());
        }
    }

    private void restoreOrAuthenticate(JoinCodeAccount account, int index) throws IOException, InterruptedException {
        if (shutdownRequested) {
            return;
        }
        boolean hasRefresh = account.refreshToken != null && !account.refreshToken.isEmpty();

        if (hasRefresh) {
            // Any refresh token is worth restoring, even without a saved
            // registration (auth succeeded but hosting failed before the last
            // shutdown): completeAuthFlow hosts a fresh code when none can be
            // resumed. The session is only discarded when Entra definitively
            // rejects the refresh token. A transient refresh failure
            // (network, 5xx) or anything that fails past this point
            // (Nethernet, Discovery) is infrastructure: the session must
            // survive it, or an outage during restore would erase the
            // operator's sign in and demand a fresh device code flow.
            try {
                refreshAccessToken(account);
            } catch (Exception e) {
                if (isLoginRejected(e)) {
                    extension.logger().warning(LOG_PREFIX + "The login for account #" + (index + 1) +
                            " was rejected, re-authenticating...");
                    extension.logger().debug(LOG_PREFIX + "Rejection detail: " + e.getMessage());
                    reauthenticate(account, index);
                } else {
                    extension.logger().warning(LOG_PREFIX + "Token refresh failed for account #" + (index + 1) +
                            ": " + e.getMessage() + " (sign in kept; retrying automatically)");
                    scheduleRestoreRetry(account, index);
                }
                return;
            }
            extension.logger().debug(LOG_PREFIX + "Restoring account #" + (index + 1) +
                    " (" + account.displayLabel() + ")...");
            try {
                completeAuthFlow(account, index);
                logAccountActive(account, index);
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to restore the join code for account #" + (index + 1) +
                        ": " + e.getMessage() + " (sign in kept; retrying automatically)");
                scheduleRestoreRetry(account, index);
            }
            return;
        }

        startDeviceCodeFlow(account, index);
    }

    /**
     * Runs the device code flow for an unattended context (startup restore or
     * forced reauthentication; the /edu joincode add command has its own
     * handling). Every terminal failure schedules its own recovery, since no
     * other task exists for the account at this point and the alternative is
     * an account that sits idle until a restart.
     */
    private void startDeviceCodeFlow(JoinCodeAccount account, int index) {
        cancelDeviceCodeRetry(account);
        if (shutdownRequested || !accounts.contains(account)) {
            return;
        }
        try {
            doDeviceCodeFlow(account, index).thenRun(() -> {
                // The sign in can complete long after it was requested; the
                // account may have been removed or the server stopped since.
                if (shutdownRequested || !accounts.contains(account)) {
                    return;
                }
                try {
                    completeAuthFlow(account, index);
                    logAccountActive(account, index);
                } catch (Exception e) {
                    extension.logger().error(LOG_PREFIX + "Failed to start join code for account #" + (index + 1) +
                            ": " + e.getMessage() + " (sign in kept; retrying automatically)");
                    scheduleRestoreRetry(account, index);
                }
            }).exceptionally(ex -> {
                // The sign in never completed, typically an expired device
                // code. Prompt again with a fresh one.
                extension.logger().error(LOG_PREFIX + "Auth failed for account #" + (index + 1) + ": " + ex.getMessage());
                scheduleDeviceCodeRetry(account, index);
                return null;
            });
        } catch (Exception e) {
            // The device code request itself failed (network); no banner was
            // shown yet, retry quietly.
            extension.logger().warning(LOG_PREFIX + "Could not start the sign in for account #" + (index + 1) +
                    ": " + e.getMessage() + " (retrying in " + RESTORE_RETRY_INTERVAL_SECONDS + " seconds)");
            scheduleDeviceCodeRetry(account, index);
        }
    }

    private void scheduleDeviceCodeRetry(JoinCodeAccount account, int index) {
        if (shutdownRequested || !accounts.contains(account)) {
            return;
        }
        deviceCodeRetryTasks.computeIfAbsent(account, ignored -> scheduler.schedule(() -> {
            deviceCodeRetryTasks.remove(account);
            startDeviceCodeFlow(account, index);
        }, RESTORE_RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS));
        if (shutdownRequested || !accounts.contains(account)) {
            cancelDeviceCodeRetry(account);
        }
    }

    private void cancelDeviceCodeRetry(JoinCodeAccount account) {
        ScheduledFuture<?> task = deviceCodeRetryTasks.remove(account);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void completeAuthFlow(JoinCodeAccount account, int index) throws Exception {
        if (!ensureNethernet()) {
            throw new IOException("Nethernet server not available");
        }

        NetworkIdParts parts = requireNetworkIdParts();
        account.discoveryClient = new DiscoveryClient(extension.logger(), account.accessToken);

        // Try to resume the existing registration so the join code survives a restart.
        // Only safe when we have a saved code and both halves of the networkId it was
        // hosted with still match: the user editable connection id number and Geyser's
        // account bound pmid each invalidate the registration when they change.
        // Sessions saved before the envelope switch have no pmid, fail the match, and
        // re-host in the new format automatically.
        boolean canResume = account.serverToken != null && account.passcode != null
                && parts.connectionId().equals(account.connectionId)
                && parts.pmid().equals(account.pmid);
        if (canResume) {
            account.discoveryClient.setServerToken(account.serverToken);
            account.discoveryClient.setPasscode(account.passcode);
            if (account.discoveryClient.heartbeat() == DiscoveryClient.HeartbeatResult.OK) {
                account.humanReadableCode = DiscoveryClient.parseJoinCode(account.passcode);
                account.extractTenantId();
                account.rehosting = false;
                account.active = true;
                scheduleHeartbeat(account);
                saveAllAccounts();
                extension.logger().debug(LOG_PREFIX + "Resumed existing join code for tenant " +
                        account.displayLabel());
                return;
            }
            // The saved registration has expired. Fall through and host a new code.
            extension.logger().debug(LOG_PREFIX + "Saved join code no longer active; hosting a new one for tenant " +
                    account.displayLabel());
        } else if (account.serverToken != null && account.passcode != null) {
            // A saved registration exists but points at different networkId
            // halves: an edited connection id, a reset account identity, or a
            // session from before the envelope switch. Its code dies here, so
            // say so instead of silently handing out a different one.
            extension.logger().info(LOG_PREFIX + "Saved join code for tenant " + account.displayLabel() +
                    " was registered with a different connection identity; hosting a new one" +
                    " (the old code and share link no longer work)");
        }

        String code = account.discoveryClient.host(parts.envelope(), worldName, hostName, maxPlayers);
        if (code == null) {
            throw new IOException("Failed to register with Discovery API");
        }

        account.humanReadableCode = code;
        account.passcode = account.discoveryClient.getPasscode();
        account.serverToken = account.discoveryClient.getServerToken();
        account.connectionId = parts.connectionId();
        account.pmid = parts.pmid();
        account.extractTenantId();
        account.rehosting = false;
        account.active = true;

        scheduleHeartbeat(account);
        saveAllAccounts();
    }

    private void scheduleRestoreRetry(JoinCodeAccount account, int index) {
        if (shutdownRequested || !accounts.contains(account)) {
            return;
        }
        account.rehosting = true;
        restoreRetryTasks.computeIfAbsent(account, ignored -> scheduler.scheduleWithFixedDelay(
                () -> retryRestore(account, index),
                RESTORE_RETRY_INTERVAL_SECONDS,
                RESTORE_RETRY_INTERVAL_SECONDS,
                TimeUnit.SECONDS));
    }

    private void retryRestore(JoinCodeAccount account, int index) {
        if (shutdownRequested || !accounts.contains(account)) {
            cancelRestoreRetry(account);
            return;
        }
        try {
            long now = System.currentTimeMillis() / 1000;
            if (account.accessToken == null
                    || account.accessTokenExpires <= now + ACCESS_TOKEN_REFRESH_MARGIN_SECONDS) {
                try {
                    refreshAccessToken(account);
                } catch (Exception e) {
                    if (isLoginRejected(e)) {
                        extension.logger().warning(LOG_PREFIX + "The login for account #" + (index + 1) +
                                " was rejected, re-authenticating...");
                        extension.logger().debug(LOG_PREFIX + "Rejection detail: " + e.getMessage());
                        reauthenticate(account, index);
                        return;
                    }
                    throw e;
                }
            }
            completeAuthFlow(account, index);
            cancelRestoreRetry(account);
            logAccountActive(account, index);
        } catch (Exception e) {
            extension.logger().warning(LOG_PREFIX + "Join code restore retry failed for account #" + (index + 1) +
                    ": " + e.getMessage() + " (retrying in " + RESTORE_RETRY_INTERVAL_SECONDS + " seconds)");
        }
    }

    private void cancelRestoreRetry(JoinCodeAccount account) {
        ScheduledFuture<?> task = restoreRetryTasks.remove(account);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Whether an exception from the Entra token endpoint means the saved
     * login itself is dead. Per Microsoft's token endpoint error reference,
     * invalid_grant (an expired or revoked refresh token), interaction_required,
     * and consent_required all demand a new interactive sign in; everything
     * else (network failures, server_error, temporarily_unavailable) is
     * retryable without touching the session. The endpoint's response body
     * rides in the exception message, the same mechanism the device code
     * poller uses for authorization_pending and slow_down.
     */
    private static boolean isLoginRejected(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("invalid_grant")
                || msg.contains("interaction_required")
                || msg.contains("consent_required"));
    }

    /**
     * The saved refresh token was definitively rejected; only a fresh device
     * code sign in can revive the account. Cancels the account's scheduled
     * work first so the eventual completeAuthFlow schedules its heartbeat
     * exactly once.
     */
    private void reauthenticate(JoinCodeAccount account, int index) {
        cancelRestoreRetry(account);
        cancelDeviceCodeRetry(account);
        cancelAccountTasks(account);
        clearAccountSession(account);
        startDeviceCodeFlow(account, index);
    }

    private void cancelAccountTasks(JoinCodeAccount account) {
        List<ScheduledFuture<?>> tasks = accountTasks.remove(account);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }
    }

    private void logAccountActive(JoinCodeAccount account, int index) {
        extension.logger().info(LOG_PREFIX + "Account #" + (index + 1) + " active: " +
                (account.humanReadableCode != null ? account.humanReadableCode : "unknown") +
                " (" + account.displayLabel() + ")" +
                (account.passcode != null ? " | " + DiscoveryClient.createShareLink(account.passcode) : ""));
    }

    // ---- Add Account (command-triggered) ----

    public void addAccount(CommandSource source) {
        JoinCodeAccount account = new JoinCodeAccount();
        int index = accounts.size();
        accounts.add(account);
        source.sendMessage(LOG_PREFIX + "Starting device code flow for new join code #" + (index + 1) + "...");

        scheduler.execute(() -> {
            try {
                doDeviceCodeFlow(account, index).thenRun(() -> {
                    // Same guard as the unattended flow: the account may have
                    // been removed or the server stopped during the sign in.
                    if (shutdownRequested || !accounts.contains(account)) {
                        return;
                    }
                    try {
                        completeAuthFlow(account, index);
                        source.sendMessage(LOG_PREFIX + "Join code #" + (index + 1) + " active: " +
                                (account.humanReadableCode != null ? account.humanReadableCode : "unknown") +
                                " (" + account.displayLabel() + ")");
                        if (account.passcode != null) {
                            source.sendMessage(LOG_PREFIX + "Link: " + DiscoveryClient.createShareLink(account.passcode));
                        }
                    } catch (Exception e) {
                        extension.logger().error(LOG_PREFIX + "Failed to start join code: " + e.getMessage());
                        accounts.remove(account);
                        source.sendMessage(LOG_PREFIX + "Failed: " + e.getMessage());
                    }
                }).exceptionally(ex -> {
                    extension.logger().error(LOG_PREFIX + "Auth failed: " + ex.getMessage());
                    accounts.remove(account);
                    source.sendMessage(LOG_PREFIX + "Authentication failed: " + ex.getMessage());
                    return null;
                });
            } catch (Exception e) {
                accounts.remove(account);
                source.sendMessage(LOG_PREFIX + "Failed to start auth: " + e.getMessage());
            }
        });
    }

    public void removeAccount(CommandSource source, int number) {
        int index = number - 1;
        if (index < 0 || index >= accounts.size()) {
            source.sendMessage(LOG_PREFIX + "Invalid account number. Use '/edu joincode' to see accounts.");
            return;
        }
        JoinCodeAccount account = accounts.get(index);

        cancelRestoreRetry(account);
        cancelDeviceCodeRetry(account);
        cancelAccountTasks(account);

        // No dehost. With its heartbeat task cancelled above, the code stops being
        // beaten and ages out on its own within the server's window.

        String oldCode = account.humanReadableCode;
        accounts.remove(account);
        saveAllAccounts();

        source.sendMessage(LOG_PREFIX + "Removed join code #" + number +
                (oldCode != null ? " (" + oldCode + ")" : "") +
                (" " + account.displayLabel()));
    }

    // ---- Nethernet (via Geyser API) ----

    private boolean ensureNethernet() {
        NethernetManager manager = extension.geyserApi().nethernetManager();
        if (manager == null) {
            extension.logger().error(LOG_PREFIX + "Nethernet transport is not available. " +
                    "Check that WebRTC native libraries are present and the connection ID file is valid.");
            return false;
        }
        if (manager.isRunning()) {
            return true;
        }
        return manager.start();
    }

    /**
     * The two halves of the networkId that join codes are registered with: the
     * user editable connection id number from connection-id.yml and the 32 hex
     * pmid of Geyser's signaling MCToken. The pmid is bound to the anonymous
     * PlayFab account (re-auth with the same CustomId keeps it, verified),
     * whose identity Geyser persists beside the connection id, so both halves
     * are stable across restarts while their files are untouched.
     */
    private record NetworkIdParts(String connectionId, String pmid) {

        /**
         * The Discovery networkId registered for join codes: the JSON
         * envelope form of the two halves. Clients require pure JSON here (a
         * digits prefix breaks their parsing). Pre-26.x clients could not use
         * an envelope registration, but they died with the 26.32 auto update,
         * along with the Type 3 signaling their numeric registrations pointed
         * at.
         */
        String envelope() {
            JsonObject envelope = new JsonObject();
            envelope.addProperty("nnid", connectionId);
            envelope.addProperty("pmid", pmid);
            envelope.addProperty("type", "jsonrpc");
            return envelope.toString();
        }
    }

    private @Nullable NetworkIdParts getNetworkIdParts() {
        NethernetManager manager = extension.geyserApi().nethernetManager();
        if (manager == null) {
            return null;
        }
        String connectionId = manager.getConnectionId();
        String pmsgId = manager.getPmsgId();
        if (connectionId == null || pmsgId == null) {
            return null;
        }
        return new NetworkIdParts(connectionId, pmsgId.replace("-", "").toLowerCase());
    }

    /**
     * Both halves are available the moment ensureNethernet() has returned
     * true: the manager's start() runs the whole PlayFab chain synchronously
     * and the pmid comes out of exactly that MCToken. A missing pmid means
     * the MCToken carries no pmid claim at all, which waiting cannot fix, so
     * this fails immediately rather than retrying.
     */
    private NetworkIdParts requireNetworkIdParts() throws IOException {
        NetworkIdParts parts = getNetworkIdParts();
        if (parts == null) {
            throw new IOException("no connection id or pmid available (MCToken without a pmid claim?)");
        }
        return parts;
    }

    /** The connection ID clients type: the two halves concatenated. */
    private @Nullable String getFullConnectionId() {
        NetworkIdParts parts = getNetworkIdParts();
        return parts != null ? parts.connectionId() + parts.pmid() : null;
    }

    private static String aliveMarker(boolean alive) {
        return " (" + (alive ? "alive" : "dead") + ")";
    }

    private void logConnectionId() {
        String connectionId = getFullConnectionId();
        extension.logger().info(LOG_PREFIX + "Connection ID: "
                + (connectionId != null ? connectionId : "unavailable"));
    }

    // ---- Heartbeat (per account) ----

    private void scheduleHeartbeat(JoinCodeAccount account) {
        ScheduledFuture<?> heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested || !account.active) return;
            DiscoveryClient client = account.discoveryClient;
            if (client == null) return;
            // Heartbeats carry no networkId, so Discovery keeps a registration
            // alive even when its envelope points at halves we no longer hold.
            // If either half drifted under the running process, the code
            // resolves to a dead identity; detect that here and rehost.
            NetworkIdParts parts = getNetworkIdParts();
            if (parts != null && (!parts.connectionId().equals(account.connectionId)
                    || !parts.pmid().equals(account.pmid))) {
                extension.logger().warning(LOG_PREFIX + "Connection identity changed; the join code for tenant " +
                        account.displayLabel() + " points at the old identity, hosting a new one...");
                rehost(account);
                return;
            }
            switch (client.heartbeat()) {
                case OK -> account.rehosting = false;
                case TRANSIENT -> extension.logger().warning(LOG_PREFIX + "Heartbeat failed for tenant " +
                        account.displayLabel());
                case REGISTRATION_DEAD -> rehost(account);
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        accountTasks.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(heartbeatTask);
    }

    /**
     * Replace a registration that can no longer be heartbeat. A dead code cannot
     * be revived, so the only recovery is hosting a new one. The account's saved
     * Entra access token is hours or days old by now (they live about an hour),
     * so it must be refreshed before /host, and the DiscoveryClient rebuilt since
     * it binds the access token at construction. Runs inside the heartbeat task;
     * on failure the next heartbeat hits the dead registration again and retries.
     */
    private void rehost(JoinCodeAccount account) {
        account.rehosting = true;
        extension.logger().warning(LOG_PREFIX + "Join code " +
                (account.humanReadableCode != null ? account.humanReadableCode + " " : "") +
                "for tenant " + account.displayLabel() + " is no longer valid, trying to obtain a new one...");
        try {
            try {
                refreshAccessToken(account);
            } catch (Exception e) {
                if (isLoginRejected(e)) {
                    int index = accounts.indexOf(account);
                    extension.logger().warning(LOG_PREFIX + "The login for tenant " + account.displayLabel() +
                            " was rejected, re-authenticating...");
                    extension.logger().debug(LOG_PREFIX + "Rejection detail: " + e.getMessage());
                    if (index >= 0) {
                        reauthenticate(account, index);
                    }
                    return;
                }
                throw e;
            }
            NetworkIdParts parts = requireNetworkIdParts();
            DiscoveryClient client = new DiscoveryClient(extension.logger(), account.accessToken);
            String code = client.host(parts.envelope(), worldName, hostName, maxPlayers);
            if (code == null) {
                throw new IOException("Failed to register with Discovery API");
            }
            account.discoveryClient = client;
            account.humanReadableCode = code;
            account.passcode = client.getPasscode();
            account.serverToken = client.getServerToken();
            account.connectionId = parts.connectionId();
            account.pmid = parts.pmid();
            account.extractTenantId();
            account.rehosting = false;
            saveAllAccounts();
            extension.logger().info(LOG_PREFIX + "New join code for " + account.displayLabel() + ": " + code +
                    (account.passcode != null ? " | " + DiscoveryClient.createShareLink(account.passcode) : ""));
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Rehost failed for tenant " + account.displayLabel() +
                    ": " + e.getMessage() + " (retrying on the next heartbeat)");
        }
    }

    private void scheduleCodeReminder() {
        codeReminderTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested) return;
            // The connection ID works without any join code, so it is always
            // worth reprinting.
            logConnectionId();
            for (JoinCodeAccount a : accounts) {
                if (a.rehosting) {
                    extension.logger().info(LOG_PREFIX + "  " + a.displayLabel() +
                            ": trying to obtain a new join code...");
                } else if (a.active && a.humanReadableCode != null && a.passcode != null) {
                    extension.logger().info(LOG_PREFIX + "  " + a.displayLabel() + ": " +
                            a.humanReadableCode + " | " + DiscoveryClient.createShareLink(a.passcode));
                }
            }
        }, CODE_REMINDER_INTERVAL_SECONDS, CODE_REMINDER_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // ---- Device Code OAuth (per account) ----

    private CompletableFuture<Void> doDeviceCodeFlow(JoinCodeAccount account, int index) throws IOException {
        String deviceCodeBody = "client_id=" + URLEncoder.encode(EDU_CLIENT_ID, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

        JsonObject response = postForm(ENTRA_BASE + "/devicecode", deviceCodeBody);

        String deviceCode = response.get("device_code").getAsString();
        String userCode = response.get("user_code").getAsString();
        String verificationUri = response.has("verification_uri")
                ? response.get("verification_uri").getAsString()
                : response.get("verification_url").getAsString();
        int expiresIn = response.get("expires_in").getAsInt();
        int initialInterval = response.get("interval").getAsInt();

        extension.logger().info(LOG_PREFIX + "============================================");
        extension.logger().info(LOG_PREFIX + "  Account #" + (index + 1) + ": Sign in with an education account");
        extension.logger().info(LOG_PREFIX + "  Go to: " + verificationUri);
        extension.logger().info(LOG_PREFIX + "  Enter code: " + userCode);
        extension.logger().info(LOG_PREFIX + "============================================");

        String pollBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(EDU_CLIENT_ID, StandardCharsets.UTF_8)
                + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

        CompletableFuture<Void> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
        AtomicInteger interval = new AtomicInteger(initialInterval);

        schedulePollTick(future, account, index, pollBody, deadline, interval);
        return future;
    }

    private void schedulePollTick(CompletableFuture<Void> future, JoinCodeAccount account, int index,
                                  String pollBody, long deadline, AtomicInteger interval) {
        scheduler.schedule(() -> {
            if (future.isDone()) return;
            if (shutdownRequested) {
                future.completeExceptionally(new IOException("Interrupted by shutdown"));
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                future.completeExceptionally(new IOException("Device code expired"));
                return;
            }
            try {
                JsonObject response = postForm(ENTRA_BASE + "/token", pollBody);
                if (response.has("access_token")) {
                    account.accessToken = response.get("access_token").getAsString();
                    account.refreshToken = response.has("refresh_token")
                            ? response.get("refresh_token").getAsString() : null;
                    account.accessTokenExpires = parseTokenExpiry(response);
                    account.extractTokenClaims();
                    extension.logger().debug(LOG_PREFIX + "Account #" + (index + 1) + " authenticated as " + account.displayLabel());
                    future.complete(null);
                    return;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("authorization_pending")) {
                    schedulePollTick(future, account, index, pollBody, deadline, interval);
                    return;
                }
                if (msg != null && msg.contains("slow_down")) {
                    interval.addAndGet(5);
                    schedulePollTick(future, account, index, pollBody, deadline, interval);
                    return;
                }
                if (msg != null && msg.contains("expired_token")) {
                    future.completeExceptionally(new IOException("Device code expired before sign-in"));
                    return;
                }
                future.completeExceptionally(e);
                return;
            }
            schedulePollTick(future, account, index, pollBody, deadline, interval);
        }, interval.get(), TimeUnit.SECONDS);
    }

    // ---- Token Refresh (per account) ----

    private void refreshAccessToken(JoinCodeAccount account) throws IOException {
        if (account.refreshToken == null) {
            throw new IOException("No refresh token available");
        }
        String body = "grant_type=refresh_token"
                + "&client_id=" + URLEncoder.encode(EDU_CLIENT_ID, StandardCharsets.UTF_8)
                + "&refresh_token=" + URLEncoder.encode(account.refreshToken, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

        JsonObject response = postForm(ENTRA_BASE + "/token", body);
        if (!response.has("access_token")) {
            throw new IOException("Token refresh failed: no access_token in response");
        }

        account.accessToken = response.get("access_token").getAsString();
        account.refreshToken = response.has("refresh_token")
                ? response.get("refresh_token").getAsString() : account.refreshToken;
        account.accessTokenExpires = parseTokenExpiry(response);
        account.extractTokenClaims();
        saveAllAccounts();
    }

    // ---- Command Handler ----

    public void handleCommand(CommandSource source, String[] args) {
        if (args.length == 0) {
            showStatus(source);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "add" -> addAccount(source);
            case "remove" -> {
                if (args.length < 2) {
                    source.sendMessage(LOG_PREFIX + "Usage: /edu joincode remove <number>");
                    return;
                }
                try {
                    removeAccount(source, Integer.parseInt(args[1]));
                } catch (NumberFormatException e) {
                    source.sendMessage(LOG_PREFIX + "Invalid number: " + args[1]);
                }
            }
            case "rebuild" -> forceRebuild(source);
            default -> showStatus(source);
        }
    }

    private void forceRebuild(CommandSource source) {
        NethernetManager manager = extension.geyserApi().nethernetManager();
        if (manager == null || !manager.isRunning()) {
            source.sendMessage(LOG_PREFIX + "Nethernet server not running.");
            return;
        }
        source.sendMessage(LOG_PREFIX + "Forcing signaling rebuild...");
        scheduler.execute(() -> {
            if (manager.restartSignaling()) {
                source.sendMessage(LOG_PREFIX + "Rebuilt successfully");
            } else {
                source.sendMessage(LOG_PREFIX + "Rebuild failed");
            }
        });
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(LOG_PREFIX + "=== Join Codes ===");
        NethernetManager manager = extension.geyserApi().nethernetManager();
        String connectionId = getFullConnectionId();
        source.sendMessage("  Connection ID: "
                + (connectionId != null
                        ? connectionId + aliveMarker(manager != null && manager.isSignalingAlive())
                        : "unavailable"));
        if (accounts.isEmpty()) {
            source.sendMessage("  No join codes registered. Use '/edu joincode add' to add one.");
            return;
        }
        for (int i = 0; i < accounts.size(); i++) {
            JoinCodeAccount a = accounts.get(i);
            String tenant = a.displayLabel();
            if (a.rehosting) {
                source.sendMessage("  #" + (i + 1) + " | " + tenant +
                        " | trying to obtain a new join code");
                continue;
            }
            String status = a.active ? "active" : "inactive";
            String code = a.humanReadableCode != null ? a.humanReadableCode : "none";
            source.sendMessage("  #" + (i + 1) + " | " + tenant + " | code: " + code + " | " + status);
            if (a.active && a.passcode != null) {
                source.sendMessage("       link: " + DiscoveryClient.createShareLink(a.passcode));
            }
        }
    }

    // ---- Config & Session Persistence ----

    private boolean loadConfig() {
        Path configPath = extension.dataFolder().resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            try {
                Files.writeString(configPath,
                        "# EduGeyser Join Code Configuration\n\n" +
                        "# World name shown to joining clients.\n" +
                        "world-name: \"Education Server\"\n\n" +
                        "# Host name shown to joining clients.\n" +
                        "host-name: \"EduGeyser\"\n\n" +
                        "# Maximum players shown.\n" +
                        "max-players: 40\n");
            } catch (IOException e) {
                extension.logger().error(LOG_PREFIX + "Failed to create config: " + e.getMessage());
                return false;
            }
            return true;
        }
        try {
            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(configPath).build();
            var node = loader.load();
            worldName = node.node("world-name").getString("Education Server");
            hostName = node.node("host-name").getString("EduGeyser");
            maxPlayers = node.node("max-players").getInt(40);
            return true;
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Failed to load config: " + e.getMessage());
            return false;
        }
    }

    private void loadAllAccounts() {
        Path sessionPath = extension.dataFolder().resolve(SESSION_FILE);
        if (!Files.exists(sessionPath)) return;
        synchronized (fileLock) {
            try {
                var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                        .path(sessionPath).build();
                var root = loader.load();
                var accountsNode = root.node("accounts");
                if (accountsNode.isList()) {
                    for (var node : accountsNode.childrenList()) {
                        JoinCodeAccount a = new JoinCodeAccount();
                        a.refreshToken = node.node("refresh-token").getString();
                        a.accessToken = node.node("access-token").getString();
                        a.accessTokenExpires = node.node("access-token-expires").getLong(0);
                        a.passcode = node.node("passcode").getString();
                        a.serverToken = node.node("server-token").getString();
                        a.connectionId = node.node("connection-id").getString();
                        a.pmid = node.node("pmid").getString();
                        a.extractTenantId();
                        a.extractTokenClaims();
                        if (a.passcode != null) {
                            a.humanReadableCode = DiscoveryClient.parseJoinCode(a.passcode);
                        }
                        accounts.add(a);
                    }
                }
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to load sessions: " + e.getMessage());
            }
        }
    }

    private void saveAllAccounts() {
        synchronized (fileLock) {
            Path path = extension.dataFolder().resolve(SESSION_FILE);
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("# EduGeyser Join Code Sessions\n");
                sb.append("# Managed automatically. Do not edit.\n\n");
                sb.append("accounts:\n");
                for (JoinCodeAccount a : accounts) {
                    sb.append("  - refresh-token: ").append(yamlStr(a.refreshToken)).append("\n");
                    sb.append("    access-token: ").append(yamlStr(a.accessToken)).append("\n");
                    sb.append("    access-token-expires: ").append(a.accessTokenExpires).append("\n");
                    sb.append("    passcode: ").append(yamlStr(a.passcode)).append("\n");
                    sb.append("    server-token: ").append(yamlStr(a.serverToken)).append("\n");
                    sb.append("    connection-id: ").append(yamlStr(a.connectionId)).append("\n");
                    sb.append("    pmid: ").append(yamlStr(a.pmid)).append("\n");
                }
                Files.writeString(path, sb.toString());
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to save sessions: " + e.getMessage());
            }
        }
    }

    private void clearAccountSession(JoinCodeAccount account) {
        account.refreshToken = null;
        account.accessToken = null;
        account.accessTokenExpires = 0;
        account.passcode = null;
        account.serverToken = null;
        account.connectionId = null;
        account.pmid = null;
        account.humanReadableCode = null;
        account.rehosting = false;
        account.active = false;
        saveAllAccounts();
    }

    // ---- HTTP Helpers ----

    private JsonObject postForm(String url, String formBody) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(formBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String err = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + err);
            }

            try (var isr = new java.io.InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(isr).getAsJsonObject();
            }
        } finally {
            con.disconnect();
        }
    }

    private String readStream(java.io.@Nullable InputStream stream) throws IOException {
        if (stream == null) return "";
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static long parseTokenExpiry(JsonObject tokenResponse) {
        if (tokenResponse.has("expires_in")) {
            return System.currentTimeMillis() / 1000 + tokenResponse.get("expires_in").getAsLong();
        }
        return 0;
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String yamlStr(@Nullable String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }
}
