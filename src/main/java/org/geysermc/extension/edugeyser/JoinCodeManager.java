package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.extension.edugeyser.joincode.JoinCodeNetherNetServer;
import org.geysermc.geyser.api.command.CommandSource;

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
 * Architecture: one shared Nethernet server with a stable nethernet ID, plus
 * one Discovery registration per education tenant. Nethernet signaling and
 * Discovery are independent systems — Discovery maps join codes to nethernet
 * IDs, the Nethernet signaling server just routes WebRTC connections. So we
 * run ONE signaling connection and point all tenant join codes at it.
 *
 * The nethernet ID is user-facing: clients can enter it directly to join
 * cross-tenant, bypassing join codes entirely. It's persisted so it survives
 * restarts.
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
    private static final long CODE_REMINDER_INTERVAL_SECONDS = 180;
    private static final long SIGNALING_RECONNECT_INTERVAL_SECONDS = 120;
    private static final long FORCED_REBUILD_INTERVAL_SECONDS = 1800; // 30 min safety net
    private static final long MIN_NETHERNET_ID = 10_000L; // 5-digit minimum to avoid trivial collisions

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final Object fileLock = new Object();
    private final Object serverLock = new Object();
    private final List<JoinCodeAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<JoinCodeAccount, List<ScheduledFuture<?>>> accountTasks = new ConcurrentHashMap<>();
    private volatile @Nullable ScheduledFuture<?> codeReminderTask;
    private volatile @Nullable ScheduledFuture<?> signalingReconnectTask;
    private volatile @Nullable ScheduledFuture<?> forcedRebuildTask;
    private volatile boolean shutdownRequested;

    // Shared Nethernet server — one signaling WebSocket, all accounts' Discovery
    // registrations point to this same nethernet ID.
    private volatile @Nullable JoinCodeNetherNetServer netherNetServer;
    private volatile long netherNetId;

    // Global config shared by all accounts
    private String worldName = "Education Server";
    private String hostName = "EduGeyser";
    private String serverIp = "";
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
        }
        loadConfig();
        resolveServerIp();
        loadAllAccounts();

        // Restore existing accounts — each account's restore path calls
        // ensureNetherNetServer() lazily, so the shared server only spins up
        // if there's at least one account to host. On a fresh install with no
        // accounts, nothing starts until /edu joincode add is run.
        for (int i = 0; i < accounts.size(); i++) {
            final int idx = i;
            JoinCodeAccount account = accounts.get(i);
            scheduler.execute(() -> runAuthFlow(account, idx));
        }

        scheduleCodeReminder();
        scheduleSignalingReconnect();
        scheduleForcedRebuild();
    }

    public void shutdown() {
        shutdownRequested = true;
        if (codeReminderTask != null) codeReminderTask.cancel(false);
        if (signalingReconnectTask != null) signalingReconnectTask.cancel(false);
        if (forcedRebuildTask != null) forcedRebuildTask.cancel(false);
        for (List<ScheduledFuture<?>> tasks : accountTasks.values()) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }

        for (JoinCodeAccount account : accounts) {
            if (account.discoveryClient != null && account.passcode != null) {
                try {
                    account.discoveryClient.dehost();
                } catch (Exception e) {
                    extension.logger().warning(LOG_PREFIX + "Dehost failed for tenant " +
                            account.displayLabel() + ": " + e.getMessage());
                }
            }
        }

        synchronized (serverLock) {
            if (netherNetServer != null) {
                netherNetServer.shutdown();
                netherNetServer = null;
            }
        }

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
        boolean hasRefresh = account.refreshToken != null && !account.refreshToken.isEmpty();

        if (hasRefresh && account.passcode != null) {
            try {
                refreshAccessToken(account);
                extension.logger().debug(LOG_PREFIX + "Restoring account #" + (index + 1) +
                        " (" + account.displayLabel() + ")...");
                completeAuthFlow(account, index);
                logAccountActive(account, index);
                return;
            } catch (Exception e) {
                extension.logger().warning(LOG_PREFIX + "Token refresh failed for account #" + (index + 1) + ", re-authenticating...");
                clearAccountSession(account);
            }
        }

        if (hasRefresh) {
            extension.logger().debug(LOG_PREFIX + "Partial session for account #" + (index + 1) + ", re-authenticating...");
            clearAccountSession(account);
        }

        doDeviceCodeFlow(account, index).thenRun(() -> {
            try {
                completeAuthFlow(account, index);
                logAccountActive(account, index);
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to start join code for account #" + (index + 1) + ": " + e.getMessage());
            }
        }).exceptionally(ex -> {
            extension.logger().error(LOG_PREFIX + "Auth failed for account #" + (index + 1) + ": " + ex.getMessage());
            return null;
        });
    }

    private void completeAuthFlow(JoinCodeAccount account, int index) throws Exception {
        // Ensure the shared Nethernet server is up before registering with Discovery.
        // If another account's flow is currently starting it, we wait on serverLock.
        if (!ensureNetherNetServer()) {
            throw new IOException("Shared Nethernet server not available");
        }

        // Register with Discovery — points this account's join code at the shared nethernetId.
        account.discoveryClient = new DiscoveryClient(extension.logger(), account.accessToken);

        String code = account.discoveryClient.host(netherNetId, worldName, hostName, maxPlayers);
        if (code == null) {
            throw new IOException("Failed to register with Discovery API");
        }

        account.humanReadableCode = code;
        account.passcode = account.discoveryClient.getPasscode();
        account.serverToken = account.discoveryClient.getServerToken();
        account.extractTenantId();
        account.active = true;

        scheduleHeartbeat(account);
        saveAllAccounts();
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

        // Cancel scheduled tasks
        List<ScheduledFuture<?>> tasks = accountTasks.remove(account);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }

        // Dehost from Discovery (the shared Nethernet server keeps running for other accounts)
        if (account.discoveryClient != null && account.passcode != null) {
            try {
                account.discoveryClient.dehost();
            } catch (Exception e) {
                extension.logger().warning(LOG_PREFIX + "Dehost failed: " + e.getMessage());
            }
        }

        String oldCode = account.humanReadableCode;
        accounts.remove(account);
        saveAllAccounts();

        // If this was the last account, shut down the shared Nethernet server.
        // No accounts = no Discovery registrations = no one can reach it anyway.
        if (accounts.isEmpty()) {
            synchronized (serverLock) {
                if (netherNetServer != null) {
                    netherNetServer.shutdown();
                    netherNetServer = null;
                    extension.logger().debug(LOG_PREFIX + "Last account removed, shared Nethernet server stopped");
                }
            }
        }

        source.sendMessage(LOG_PREFIX + "Removed join code #" + number +
                (oldCode != null ? " (" + oldCode + ")" : "") +
                (" " + account.displayLabel()));
    }

    // ---- Shared Nethernet server ----

    /**
     * Ensures the shared Nethernet server is running. Idempotent and thread-safe.
     * The nethernet ID was picked at config creation time and is persistent.
     */
    private boolean ensureNetherNetServer() {
        synchronized (serverLock) {
            if (netherNetServer != null && netherNetServer.isRunning()) {
                return true;
            }

            if (netherNetId < MIN_NETHERNET_ID) {
                extension.logger().error(LOG_PREFIX + "connection-id in joincode_config.yml is " +
                        netherNetId + " — must be at least " + MIN_NETHERNET_ID +
                        " (5 digits) to avoid collisions with other servers. " +
                        "Delete joincode_config.yml to regenerate a fresh 10-digit ID.");
                return false;
            }

            try {
                PlayFabTokenManager playFab = new PlayFabTokenManager(extension.logger());
                String mcToken = playFab.authenticate();
                if (mcToken == null) {
                    extension.logger().error(LOG_PREFIX + "Failed to obtain MCToken from PlayFab");
                    return false;
                }

                String transferIp;
                int port;
                int lastColon = serverIp.lastIndexOf(':');
                if (lastColon > 0) {
                    transferIp = serverIp.substring(0, lastColon);
                    try {
                        port = Integer.parseInt(serverIp.substring(lastColon + 1));
                    } catch (NumberFormatException e) {
                        port = extension.geyserApi().bedrockListener().port();
                    }
                } else {
                    transferIp = serverIp;
                    port = extension.geyserApi().bedrockListener().port();
                }
                netherNetServer = new JoinCodeNetherNetServer(
                        extension.logger(), getBedrockCodec(), transferIp, port);

                long started = netherNetServer.start(mcToken, netherNetId);
                if (started == -1) {
                    netherNetServer = null;
                    extension.logger().error(LOG_PREFIX + "Failed to start Nethernet server");
                    return false;
                }
                extension.logger().info(LOG_PREFIX + "Connection ID: " + netherNetId +
                        " (share this directly to let clients join cross-tenant)");
                return true;
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "ensureNetherNetServer failed: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Rebuilds the shared Nethernet signaling with a fresh MCToken, preserving
     * the same nethernetID so all Discovery registrations remain valid.
     */
    private boolean rebuildSharedSignaling(String reason) {
        synchronized (serverLock) {
            if (netherNetServer == null) {
                return ensureNetherNetServer();
            }
            try {
                PlayFabTokenManager playFab = new PlayFabTokenManager(extension.logger());
                String mcToken = playFab.authenticate();
                if (mcToken == null) {
                    extension.logger().warning(LOG_PREFIX + "Rebuild (" + reason + "): failed to get MCToken");
                    return false;
                }
                if (netherNetServer.restartSignaling(mcToken)) {
                    extension.logger().info(LOG_PREFIX + "Shared signaling rebuilt (" + reason + ")");
                    return true;
                } else {
                    extension.logger().warning(LOG_PREFIX + "Shared signaling rebuild failed (" + reason + ")");
                    return false;
                }
            } catch (Exception e) {
                extension.logger().warning(LOG_PREFIX + "Shared signaling rebuild error (" + reason + "): " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Generate a random nethernet ID. The ID is user-facing (clients can enter
     * it directly to join cross-tenant) so we aim for something tractable.
     * Real education clients generate 20-digit IDs, but variable lengths work —
     * we use exactly 10 digits for consistency.
     */
    private static long generateNetherNetId() {
        // [1_000_000_000, 10_000_000_000) — always 10 digits
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
    }

    // ---- Heartbeat (per account) ----

    private void scheduleHeartbeat(JoinCodeAccount account) {
        ScheduledFuture<?> heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested || !account.active) return;
            if (account.discoveryClient != null) {
                if (!account.discoveryClient.heartbeat()) {
                    extension.logger().warning(LOG_PREFIX + "Heartbeat failed for tenant " +
                            account.displayLabel());
                }
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        accountTasks.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(heartbeatTask);
    }

    /**
     * Periodically checks the shared Nethernet signaling WebSocket and rebuilds
     * it only if dead, preserving the shared nethernetID so all Discovery
     * registrations stay valid. Existing WebRTC peer connections are unaffected.
     */
    private void scheduleSignalingReconnect() {
        signalingReconnectTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested) return;
            JoinCodeNetherNetServer server = netherNetServer;
            if (server == null) return;
            if (server.isSignalingAlive()) return;

            extension.logger().info(LOG_PREFIX + "Shared signaling dead, rebuilding...");
            rebuildSharedSignaling("reconnect");
        }, SIGNALING_RECONNECT_INTERVAL_SECONDS, SIGNALING_RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Forces a shared signaling rebuild every 30 minutes as a safety net, even
     * if the health check still reports the connection alive. Catches silent
     * half-closed TCP and any unknown failure modes.
     */
    private void scheduleForcedRebuild() {
        forcedRebuildTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested) return;
            if (netherNetServer == null) return;
            extension.logger().debug(LOG_PREFIX + "Forced 30-min shared rebuild");
            rebuildSharedSignaling("forced");
        }, FORCED_REBUILD_INTERVAL_SECONDS, FORCED_REBUILD_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleCodeReminder() {
        codeReminderTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested) return;
            boolean any = false;
            for (JoinCodeAccount a : accounts) {
                if (a.active && a.humanReadableCode != null && a.passcode != null) {
                    if (!any) {
                        extension.logger().info(LOG_PREFIX + "Connection ID: " + netherNetId);
                        any = true;
                    }
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
            case "rebuild" -> forceRebuildAll(source);
            default -> showStatus(source);
        }
    }

    /**
     * Temporary test command: force-rebuild the shared Nethernet signaling.
     * Used to verify the rebuild path works without waiting for the 30-min timer.
     */
    private void forceRebuildAll(CommandSource source) {
        JoinCodeNetherNetServer server = netherNetServer;
        if (server == null) {
            source.sendMessage(LOG_PREFIX + "Shared Nethernet server not running.");
            return;
        }
        boolean alive = server.isSignalingAlive();
        long silence = server.getSignalingSilenceMillis();
        source.sendMessage(LOG_PREFIX + "Forcing rebuild of shared signaling...");
        source.sendMessage(LOG_PREFIX + "  alive=" + alive + " | silence=" + silence + "ms | id=" + netherNetId);
        scheduler.execute(() -> {
            if (rebuildSharedSignaling("manual")) {
                source.sendMessage(LOG_PREFIX + "\u2713 Rebuilt successfully");
            } else {
                source.sendMessage(LOG_PREFIX + "\u2717 Rebuild failed");
            }
        });
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(LOG_PREFIX + "=== Join Codes ===");
        JoinCodeNetherNetServer server = netherNetServer;
        source.sendMessage("  Connection ID: " + netherNetId +
                " (" + (server != null && server.isSignalingAlive() ? "alive" : "dead") + ")");
        if (accounts.isEmpty()) {
            source.sendMessage("  No join codes registered. Use '/edu joincode add' to add one.");
            return;
        }
        for (int i = 0; i < accounts.size(); i++) {
            JoinCodeAccount a = accounts.get(i);
            String tenant = a.displayLabel();
            String status = a.active ? "active" : "inactive";
            String code = a.humanReadableCode != null ? a.humanReadableCode : "none";
            source.sendMessage("  #" + (i + 1) + " | " + tenant + " | code: " + code + " | " + status);
            if (a.active && a.passcode != null) {
                source.sendMessage("       link: " + DiscoveryClient.createShareLink(a.passcode));
            }
        }
    }

    // ---- Config & Session Persistence ----

    private void loadConfig() {
        Path configPath = extension.dataFolder().resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            // First-ever start: generate the nethernet ID now and write it
            // directly into the default config. Never touched again unless
            // the user manually edits the file.
            netherNetId = generateNetherNetId();
            try {
                Files.writeString(configPath,
                        "# EduGeyser Join Code Configuration\n\n" +
                        "# World name shown to joining clients.\n" +
                        "world-name: \"Education Server\"\n\n" +
                        "# Host name shown to joining clients.\n" +
                        "host-name: \"EduGeyser\"\n\n" +
                        "# Public IP or hostname for the TransferPacket (e.g. \"mc.example.com\").\n" +
                        "# If the port players connect with differs from the port in Geyser's config,\n" +
                        "# include it as ip:port (e.g. \"mc.example.com:19132\" when using playit.gg).\n" +
                        "# Leave empty to auto-detect.\n" +
                        "server-ip: \"\"\n\n" +
                        "# Connection ID. All join codes across all tenants point to this.\n" +
                        "# Clients can also enter this ID directly in Education Edition's\n" +
                        "# connection dialog to join cross-tenant, bypassing join codes.\n" +
                        "#\n" +
                        "# Recommended: keep this at 10 digits (the generated default).\n" +
                        "# Do NOT pick predictable numbers like 123456789 or 1111111111 —\n" +
                        "# someone else running another server could accidentally use the same\n" +
                        "# ID, causing clients to be routed to the wrong server worldwide.\n" +
                        "# Minimum enforced length is 5 digits (values below 10000 are rejected).\n" +
                        "connection-id: " + netherNetId + "\n\n" +
                        "# Maximum players shown.\n" +
                        "max-players: 40\n");
            } catch (IOException e) {
                extension.logger().error(LOG_PREFIX + "Failed to create config: " + e.getMessage());
            }
            return;
        }
        try {
            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(configPath).build();
            var node = loader.load();
            worldName = node.node("world-name").getString("Education Server");
            hostName = node.node("host-name").getString("EduGeyser");
            serverIp = node.node("server-ip").getString("");
            // Prefer new key; fall back to old key for existing installs
            long legacyId = node.node("nethernet-id").getLong(0);
            netherNetId = node.node("connection-id").getLong(legacyId);
            maxPlayers = node.node("max-players").getInt(40);
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Failed to load config: " + e.getMessage());
        }
    }

    private void resolveServerIp() {
        if (serverIp != null && !serverIp.isEmpty()) return;

        String detectedIp = detectPublicIp();
        if (detectedIp != null) {
            int port = extension.geyserApi().bedrockListener().port();
            serverIp = detectedIp.contains(":") ? "[" + detectedIp + "]:" + port : detectedIp + ":" + port;
            extension.logger().debug(LOG_PREFIX + "Auto-detected server IP: " + serverIp);
        } else {
            extension.logger().warning(LOG_PREFIX + "Could not detect public IP. Set server-ip in joincode_config.yml");
        }
    }

    private @Nullable String detectPublicIp() {
        for (String service : new String[]{"https://checkip.amazonaws.com", "https://api.ipify.org", "https://icanhazip.com"}) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) URI.create(service).toURL().openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                if (con.getResponseCode() == 200) {
                    String ip = readStream(con.getInputStream()).trim();
                    if (!ip.isEmpty() && ip.length() < 46) return ip;
                }
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
            }
        }
        return null;
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
        account.humanReadableCode = null;
        account.active = false;
        saveAllAccounts();
    }

    // ---- Codec Helper ----

    private BedrockCodec getBedrockCodec() {
        return org.geysermc.extension.edugeyser.joincode.EducationRedirectCodec.wrap(
                org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944.CODEC);
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
