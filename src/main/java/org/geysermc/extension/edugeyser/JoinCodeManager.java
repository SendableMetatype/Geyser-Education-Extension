package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.network.NethernetManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Manages Education Edition join codes (multi-account).
 *
 * Architecture: Geyser owns the Nethernet server and connection ID. This
 * manager only handles Discovery API registration (join codes) and OAuth
 * device code flows. Each education tenant gets its own Discovery
 * registration pointing at Geyser's shared connection ID.
 */
public class JoinCodeManager {

    private static final String SESSION_FILE = "sessions_joincode.yml";
    private static final String CONFIG_FILE = "joincode_config.yml";
    private static final String LOG_PREFIX = "[JoinCode] ";
    private static final long HEARTBEAT_INTERVAL_SECONDS = 100;
    private static final long RESTORE_RETRY_INTERVAL_SECONDS = HEARTBEAT_INTERVAL_SECONDS;
    private static final long ACCESS_TOKEN_REFRESH_MARGIN_SECONDS = 60;
    private static final long CODE_REMINDER_INTERVAL_SECONDS = 900;

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final EntraOAuthClient oauthClient;
    private final Object fileLock = new Object();
    private final List<JoinCodeAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<JoinCodeAccount, List<ScheduledFuture<?>>> accountTasks = new ConcurrentHashMap<>();
    private final Map<JoinCodeAccount, ScheduledFuture<?>> restoreRetryTasks = new ConcurrentHashMap<>();
    private final Map<JoinCodeAccount, ScheduledFuture<?>> deviceCodeRetryTasks = new ConcurrentHashMap<>();
    private volatile @Nullable ScheduledFuture<?> codeReminderTask;
    private volatile boolean shutdownRequested;

    // Global config shared by all accounts
    private String worldName = "World Name";
    private String hostName = "Server Name";

    public JoinCodeManager(EduGeyserExtension extension) {
        this.extension = extension;
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.oauthClient = new EntraOAuthClient(scheduler, () -> shutdownRequested);
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
                if (EntraOAuthClient.requiresInteractiveLogin(e)) {
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
            // Any non-OK result during startup falls through to hosting a new
            // code intentionally. Although a timeout or 5xx may only be
            // transient, treating startup as a recovery boundary gives an
            // operator a way to escape an indefinitely unrestorable saved
            // registration by restarting the server. The tradeoff is that a
            // transient failure here can rotate an otherwise valid join code.
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

        String code = account.discoveryClient.host(parts.envelope(), worldName, hostName);
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
                    if (EntraOAuthClient.requiresInteractiveLogin(e)) {
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

        // Best-effort immediate revocation. If dehosting fails, the cancelled
        // heartbeat still lets Discovery expire the code naturally.
        scheduler.execute(() -> {
            DiscoveryClient client = account.discoveryClient;
            if (client == null && account.serverToken != null && account.passcode != null) {
                client = new DiscoveryClient(extension.logger(), "");
                client.setServerToken(account.serverToken);
                client.setPasscode(account.passcode);
            }
            if (client != null) {
                client.dehost();
            }
        });

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
                if (EntraOAuthClient.requiresInteractiveLogin(e)) {
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
            String code = client.host(parts.envelope(), worldName, hostName);
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
        EntraOAuthClient.DeviceAuthorization authorization =
                oauthClient.requestDeviceCode(EntraOAuthClient.EDUCATION_CLIENT_ID);

        extension.logger().info(LOG_PREFIX + "============================================");
        extension.logger().info(LOG_PREFIX + "  Account #" + (index + 1) + ": Sign in with an education account");
        extension.logger().info(LOG_PREFIX + "  Go to: " + authorization.verificationUri());
        extension.logger().info(LOG_PREFIX + "  Enter code: " + authorization.userCode());
        extension.logger().info(LOG_PREFIX + "============================================");

        return oauthClient.poll(authorization).thenAccept(tokens -> {
            account.accessToken = tokens.accessToken();
            account.refreshToken = tokens.refreshToken();
            account.accessTokenExpires = tokens.accessTokenExpires();
            account.extractTokenClaims();
            extension.logger().debug(LOG_PREFIX + "Account #" + (index + 1)
                    + " authenticated as " + account.displayLabel());
        });
    }

    // ---- Token Refresh (per account) ----

    private void refreshAccessToken(JoinCodeAccount account) throws IOException {
        if (account.refreshToken == null) {
            throw new IOException("No refresh token available");
        }
        EntraOAuthClient.Tokens tokens = oauthClient.refresh(
                EntraOAuthClient.EDUCATION_CLIENT_ID, account.refreshToken);
        account.accessToken = tokens.accessToken();
        account.refreshToken = tokens.refreshToken();
        account.accessTokenExpires = tokens.accessTokenExpires();
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
                        "world-name: \"World Name\"\n\n" +
                        "# Host name shown to joining clients.\n" +
                        "host-name: \"Server Name\"\n");
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
            worldName = node.node("world-name").getString("World Name");
            hostName = node.node("host-name").getString("Server Name");
            // Older configs may still contain max-players. Configurate ignores
            // unknown keys, so it remains harmless while Discovery always gets
            // the internal non-enforcing value defined by DiscoveryClient.
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
                accounts.addAll(readAccounts(sessionPath, (entry, error) ->
                        extension.logger().error(LOG_PREFIX + "Skipping malformed session account #" + entry +
                                ": " + error.getMessage())));
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to load sessions: " + e.getMessage());
            }
        }
    }

    static List<JoinCodeAccount> readAccounts(Path sessionPath,
                                               BiConsumer<Integer, Exception> malformedEntryHandler) throws IOException {
        var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                .path(sessionPath).build();
        var accountsNode = loader.load().node("accounts");
        List<JoinCodeAccount> loadedAccounts = new ArrayList<>();
        if (!accountsNode.isList()) {
            return loadedAccounts;
        }

        int entry = 0;
        for (var node : accountsNode.childrenList()) {
            entry++;
            try {
                if (!node.isMap()) {
                    throw new IllegalArgumentException("account entry must be a mapping");
                }
                JoinCodeAccount account = new JoinCodeAccount();
                account.refreshToken = node.node("refresh-token").getString();
                account.accessToken = node.node("access-token").getString();
                account.accessTokenExpires = node.node("access-token-expires").getLong(0);
                account.passcode = node.node("passcode").getString();
                account.serverToken = node.node("server-token").getString();
                account.connectionId = node.node("connection-id").getString();
                account.pmid = node.node("pmid").getString();
                account.extractTenantId();
                account.extractTokenClaims();
                if (account.passcode != null) {
                    account.humanReadableCode = DiscoveryClient.parseJoinCode(account.passcode);
                }
                loadedAccounts.add(account);
            } catch (Exception e) {
                malformedEntryHandler.accept(entry, e);
            }
        }
        return loadedAccounts;
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
                AtomicFileWriter.writeString(path, sb.toString());
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

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String yamlStr(@Nullable String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }
}
