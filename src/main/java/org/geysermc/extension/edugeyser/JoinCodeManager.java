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
    private static final long CODE_REMINDER_INTERVAL_SECONDS = 180;

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final Object fileLock = new Object();
    private final List<JoinCodeAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<JoinCodeAccount, List<ScheduledFuture<?>>> accountTasks = new ConcurrentHashMap<>();
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

        // Nethernet server lifecycle is owned by Geyser - we don't shut it down

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
        if (!ensureNethernet()) {
            throw new IOException("Nethernet server not available");
        }

        String connectionId = getConnectionId();
        account.discoveryClient = new DiscoveryClient(extension.logger(), account.accessToken);

        String code = account.discoveryClient.host(connectionId, worldName, hostName, maxPlayers);
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

        List<ScheduledFuture<?>> tasks = accountTasks.remove(account);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }

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

    private @Nullable String getConnectionId() {
        NethernetManager manager = extension.geyserApi().nethernetManager();
        return manager != null ? manager.getConnectionId() : null;
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

    private void scheduleCodeReminder() {
        codeReminderTask = scheduler.scheduleAtFixedRate(() -> {
            if (shutdownRequested) return;
            boolean any = false;
            for (JoinCodeAccount a : accounts) {
                if (a.active && a.humanReadableCode != null && a.passcode != null) {
                    if (!any) {
                        extension.logger().info(LOG_PREFIX + "Connection ID: " + getConnectionId());
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
        String connectionId = manager != null ? manager.getConnectionId() : "unavailable";
        boolean alive = manager != null && manager.isSignalingAlive();
        source.sendMessage("  Connection ID: " + connectionId + " (" + (alive ? "alive" : "dead") + ")");
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
