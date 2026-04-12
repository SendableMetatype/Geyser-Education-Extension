package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.api.command.CommandSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages multiple MESS server list registrations.
 * Each account represents a Global Admin M365 tenant whose server list
 * will show this server. Ported from EduGeyser's EducationAuthManager.
 */
public class MessServerListManager {

    private static final String TOOLING_CLIENT_ID = "1c91b067-6806-44a5-8d2d-3137e625f5b8";
    private static final String EDU_CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2";
    private static final String SCOPE = "16556bfc-5102-43c9-a82a-3ea5e4810689/.default offline_access";
    private static final String ENTRA_BASE = "https://login.microsoftonline.com/organizations/oauth2/v2.0";
    private static final String MESS_BASE = "https://dedicatedserver.minecrafteduservices.com";
    private static final String CONFIG_FILE = "serverlist_config.yml";
    private static final String SESSION_FILE = "sessions_serverlist.yml";
    private static final String LOG_PREFIX = "[EduServerList] ";
    private static final int HTTP_TIMEOUT = 15000;
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 60;
    private static final int MESS_HEALTH_OPTIMAL = 2;

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final Object configFileLock = new Object();
    private final List<ServerListAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<ServerListAccount, List<ScheduledFuture<?>>> accountTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean shutdownRequested;

    public MessServerListManager(EduGeyserExtension extension) {
        this.extension = extension;
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    // ---- Lifecycle ----

    public void initialize() {
        loadAllAccounts();

        // Resolve global IP if not set
        if (globalServerIp == null || globalServerIp.isEmpty()) {
            String detectedIp = detectPublicIp();
            if (detectedIp != null) {
                int port = extension.geyserApi().bedrockListener().port();
                globalServerIp = formatIpPort(detectedIp, port);
                extension.logger().info(LOG_PREFIX + "Auto-detected server IP: " + globalServerIp);
            }
        }

        // Start auth flows for existing accounts
        for (int i = 0; i < accounts.size(); i++) {
            final int idx = i;
            ServerListAccount account = accounts.get(i);
            scheduler.execute(() -> runAuthFlow(account, idx));
        }
    }

    public void shutdown() {
        shutdownRequested = true;
        for (List<ScheduledFuture<?>> tasks : accountTasks.values()) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }

        for (ServerListAccount account : accounts) {
            if (account.serverToken != null) {
                try {
                    dehostServer(account);
                } catch (Exception e) {
                    extension.logger().error(LOG_PREFIX + "Failed to dehost account " + account.tenantId + ": " + e.getMessage());
                }
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

    private void runAuthFlow(ServerListAccount account, int index) {
        try {
            restoreOrAuthenticate(account, index);
        } catch (InterruptedException e) {
            extension.logger().debug(LOG_PREFIX + e.getMessage());
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Auth flow failed for account #" + (index + 1) + ": " + e.getMessage());
        }
    }

    private void completeAuthFlow(ServerListAccount account, int index) {
        try {
            if (account.serverId != null && !account.serverId.isEmpty()) {
                fetchServerToken(account);
            } else {
                registerNewServer(account);
                extension.logger().info(LOG_PREFIX + "Account #" + (index + 1) + " registered with server ID: " + account.serverId);
            }

            tryEditTenantSettings(account);
            hostServer(account);
            tryEditServerInfo(account);
            account.extractTenantId();
            account.active = true;
            saveAllAccounts();

            extension.logger().info(LOG_PREFIX + "Account #" + (index + 1) + " hosted at " + globalServerIp +
                    " (tenant: " + (account.tenantId != null ? account.tenantId : "unknown") + ")");

            if (shutdownRequested) return;
            scheduleServerUpdates(account);
            scheduleTokenRefresh(account);
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Auth flow failed for account #" + (index + 1) + ": " + e.getMessage());
        }
    }

    private void restoreOrAuthenticate(ServerListAccount account, int index) throws IOException, InterruptedException {
        boolean hasTooling = account.refreshToken != null && !account.refreshToken.isEmpty();
        boolean hasEdu = account.eduRefreshToken != null && !account.eduRefreshToken.isEmpty();

        if (hasTooling && hasEdu && account.serverId != null && !account.serverId.isEmpty()) {
            extension.logger().info(LOG_PREFIX + "Restoring account #" + (index + 1) + " (serverId=" + account.serverId + ")");
            try {
                ensureValidAccessToken(account);
                ensureValidEduAccessToken(account);
                completeAuthFlow(account, index);
                return;
            } catch (InterruptedException e) {
                // Token refresh failed — clear session and fall through to re-auth
                extension.logger().warning(LOG_PREFIX + "Token refresh failed for account #" + (index + 1) + ", re-authenticating...");
                clearAccountSession(account);
            }
        }

        if (hasTooling || hasEdu) {
            extension.logger().info(LOG_PREFIX + "Partial session for account #" + (index + 1) + ", re-authenticating...");
            clearAccountSession(account);
        }

        doDeviceCodeFlows(account, index).thenRun(() -> completeAuthFlow(account, index)).exceptionally(ex -> {
            extension.logger().error(LOG_PREFIX + "Auth failed for account #" + (index + 1) + ": " + ex.getMessage());
            return null;
        });
    }

    // ---- Add Account (command-triggered) ----

    public void addAccount(CommandSource source) {
        ServerListAccount account = new ServerListAccount();
        int index = accounts.size();
        accounts.add(account);
        source.sendMessage(LOG_PREFIX + "Starting device code flow for new account #" + (index + 1) + "...");

        scheduler.execute(() -> {
            doDeviceCodeFlows(account, index).thenRun(() -> {
                completeAuthFlow(account, index);
                source.sendMessage(LOG_PREFIX + "Account #" + (index + 1) + " registered successfully!" +
                        " Tenant: " + (account.tenantId != null ? account.tenantId : "unknown"));
            }).exceptionally(ex -> {
                extension.logger().error(LOG_PREFIX + "Failed to add account: " + ex.getMessage());
                accounts.remove(account);
                source.sendMessage(LOG_PREFIX + "Failed to add account: " + ex.getMessage());
                return null;
            });
        });
    }

    public void removeAccount(CommandSource source, int number) {
        int index = number - 1;
        if (index < 0 || index >= accounts.size()) {
            source.sendMessage(LOG_PREFIX + "Invalid account number. Use '/edu serverlist' to see accounts.");
            return;
        }
        ServerListAccount account = accounts.get(index);

        // Cancel scheduled tasks for this account
        List<ScheduledFuture<?>> tasks = accountTasks.remove(account);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }

        if (account.serverToken != null) {
            try {
                dehostServer(account);
            } catch (Exception e) {
                extension.logger().warning(LOG_PREFIX + "Could not dehost: " + e.getMessage());
            }
        }
        accounts.remove(account);
        saveAllAccounts();
        source.sendMessage(LOG_PREFIX + "Removed account #" + number +
                (account.tenantId != null ? " (tenant: " + account.tenantId + ")" : ""));
    }

    // ---- Device Code OAuth ----

    private CompletableFuture<Void> doDeviceCodeFlows(ServerListAccount account, int index) {
        extension.logger().info(LOG_PREFIX + "Account #" + (index + 1) + ": Two sign-ins required.");

        extension.logger().info(LOG_PREFIX + "Step 1/2: Sign in for server management (tooling)...");
        return doDeviceCodeFlow(TOOLING_CLIENT_ID, "tooling authentication").thenCompose(toolingTokens -> {
            account.accessToken = toolingTokens.get("access_token").getAsString();
            account.refreshToken = toolingTokens.has("refresh_token")
                    ? toolingTokens.get("refresh_token").getAsString() : null;
            account.accessTokenExpires = parseTokenExpiry(toolingTokens);

            extension.logger().info(LOG_PREFIX + "Step 2/2: Sign in for server registration...");
            return doDeviceCodeFlow(EDU_CLIENT_ID, "server authentication");
        }).thenAccept(eduTokens -> {
            account.eduAccessToken = eduTokens.get("access_token").getAsString();
            account.eduRefreshToken = eduTokens.has("refresh_token")
                    ? eduTokens.get("refresh_token").getAsString() : null;
            account.eduAccessTokenExpires = parseTokenExpiry(eduTokens);
            extension.logger().info(LOG_PREFIX + "Both authentications successful!");
        });
    }

    private CompletableFuture<JsonObject> doDeviceCodeFlow(String clientId, String label) {
        String deviceCodeBody = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        String pollBodyBase = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        try {
            JsonObject response = postForm(ENTRA_BASE + "/devicecode", deviceCodeBody);

            String deviceCode = response.get("device_code").getAsString();
            String userCode = response.get("user_code").getAsString();
            String verificationUri = response.has("verification_uri")
                    ? response.get("verification_uri").getAsString()
                    : response.get("verification_url").getAsString();
            int expiresIn = response.get("expires_in").getAsInt();
            int initialInterval = response.get("interval").getAsInt();

            extension.logger().info(LOG_PREFIX + "============================================");
            extension.logger().info(LOG_PREFIX + "  Go to: " + verificationUri);
            extension.logger().info(LOG_PREFIX + "  Enter code: " + userCode);
            extension.logger().info(LOG_PREFIX + "  (" + label + ")");
            extension.logger().info(LOG_PREFIX + "============================================");
            extension.logger().info(LOG_PREFIX + "Waiting for sign-in...");

            String pollBody = pollBodyBase + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);
            long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
            AtomicInteger interval = new AtomicInteger(initialInterval);

            schedulePollTick(future, pollBody, ENTRA_BASE + "/token", label, deadline, interval);
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void schedulePollTick(CompletableFuture<JsonObject> future, String pollBody,
                                  String tokenUrl, String label, long deadline, AtomicInteger interval) {
        scheduler.schedule(() -> {
            if (future.isDone()) return;
            if (shutdownRequested) {
                future.completeExceptionally(new IOException("Device code flow interrupted by shutdown"));
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                future.completeExceptionally(new IOException("Device code expired"));
                return;
            }
            try {
                JsonObject response = postForm(tokenUrl, pollBody);
                if (response.has("access_token")) {
                    extension.logger().info(LOG_PREFIX + "Authentication successful (" + label + ")!");
                    future.complete(response);
                    return;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("authorization_pending")) {
                    schedulePollTick(future, pollBody, tokenUrl, label, deadline, interval);
                    return;
                }
                if (msg != null && msg.contains("slow_down")) {
                    interval.addAndGet(5);
                    schedulePollTick(future, pollBody, tokenUrl, label, deadline, interval);
                    return;
                }
                if (msg != null && msg.contains("expired_token")) {
                    future.completeExceptionally(new IOException("Device code expired before user completed sign-in"));
                    return;
                }
                future.completeExceptionally(e);
                return;
            }
            schedulePollTick(future, pollBody, tokenUrl, label, deadline, interval);
        }, interval.get(), TimeUnit.SECONDS);
    }

    // ---- Token Refresh ----

    private boolean refreshAccessToken(ServerListAccount account) {
        if (account.refreshToken == null) return false;
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(TOOLING_CLIENT_ID, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(account.refreshToken, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
            JsonObject response = postForm(ENTRA_BASE + "/token", body);
            if (response.has("access_token")) {
                account.accessToken = response.get("access_token").getAsString();
                account.refreshToken = response.has("refresh_token")
                        ? response.get("refresh_token").getAsString() : account.refreshToken;
                account.accessTokenExpires = parseTokenExpiry(response);
                saveAllAccounts();
                return true;
            }
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Tooling token refresh failed: " + e.getMessage());
        }
        return false;
    }

    private boolean refreshEduAccessToken(ServerListAccount account) {
        if (account.eduRefreshToken == null) return false;
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(EDU_CLIENT_ID, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(account.eduRefreshToken, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
            JsonObject response = postForm(ENTRA_BASE + "/token", body);
            if (response.has("access_token")) {
                account.eduAccessToken = response.get("access_token").getAsString();
                account.eduRefreshToken = response.has("refresh_token")
                        ? response.get("refresh_token").getAsString() : account.eduRefreshToken;
                account.eduAccessTokenExpires = parseTokenExpiry(response);
                saveAllAccounts();
                return true;
            }
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Edu token refresh failed: " + e.getMessage());
        }
        return false;
    }

    private void ensureValidAccessToken(ServerListAccount account) throws InterruptedException {
        if (account.accessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS) return;
        if (!refreshAccessToken(account)) {
            throw new InterruptedException("Token refresh failed, re-authentication needed");
        }
    }

    private void ensureValidEduAccessToken(ServerListAccount account) throws InterruptedException {
        if (account.eduAccessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS) return;
        if (!refreshEduAccessToken(account)) {
            throw new InterruptedException("Edu token refresh failed, re-authentication needed");
        }
    }

    // ---- MESS Server Registration ----

    private void registerNewServer(ServerListAccount account) throws IOException {
        String jwtResponse = postEmptyWithAuth(MESS_BASE + "/server/register", account.eduAccessToken);
        parseServerTokenJwt(account, jwtResponse);
    }

    private void fetchServerToken(ServerListAccount account) throws IOException {
        if (account.serverId == null || account.serverId.isEmpty()) {
            throw new IOException("Cannot fetch server token: no serverId available.");
        }
        String url = MESS_BASE + "/server/fetch_token?serverId=" + URLEncoder.encode(account.serverId, StandardCharsets.UTF_8);
        String jwtResponse = getWithAuth(url, account.eduAccessToken);
        parseServerTokenJwt(account, jwtResponse);
    }

    private void parseServerTokenJwt(ServerListAccount account, String jwtResponse) throws IOException {
        account.serverTokenJwt = jwtResponse.trim();
        String[] parts = account.serverTokenJwt.split("\\.");
        if (parts.length < 2) {
            throw new IOException("Invalid JWT response (got " + parts.length + " parts, expected 3)");
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

        if (!payload.has("exp") || !payload.has("payload")) {
            throw new IOException("JWT payload missing required fields. Keys: " + payload.keySet());
        }
        account.serverTokenExpires = payload.get("exp").getAsLong();
        JsonObject inner = payload.getAsJsonObject("payload");

        if (inner.has("serverToken")) {
            account.serverToken = inner.get("serverToken").getAsString();
        } else if (inner.has("ServerToken")) {
            account.serverToken = inner.get("ServerToken").getAsString();
        } else {
            throw new IOException("JWT payload missing serverToken field. Keys: " + inner.keySet());
        }

        if (account.serverId == null || account.serverId.isEmpty()) {
            if (inner.has("serverId")) account.serverId = inner.get("serverId").getAsString();
            else if (inner.has("ServerId")) account.serverId = inner.get("ServerId").getAsString();
        }

        account.extractTenantId();
    }

    // ---- Tenant Settings & Server Info ----

    private void tryEditTenantSettings(ServerListAccount account) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("DedicatedServerEnabled", true);
            body.addProperty("TeachersAllowed", true);
            body.addProperty("CrossTenantAllowed", true);
            postJsonWithAuth(MESS_BASE + "/tooling/edit_tenant_settings", account.accessToken, body.toString());
            extension.logger().info(LOG_PREFIX + "Tenant settings configured: dedicated servers enabled, teacher access, cross-tenant.");
        } catch (IOException e) {
            extension.logger().warning(LOG_PREFIX + "Could not update tenant settings (may require Global Admin): " + e.getMessage());
            extension.logger().warning(LOG_PREFIX + "  https://education.minecraft.net/teachertools/en_US/dedicatedservers/");
        }
    }

    private void tryEditServerInfo(ServerListAccount account) {
        if (account.serverId == null || account.serverId.isEmpty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("ServerId", account.serverId);
            if (globalServerName != null && !globalServerName.isEmpty()) {
                body.addProperty("ServerName", globalServerName);
            }
            body.addProperty("Enabled", true);
            body.addProperty("IsBroadcasted", true);
            body.addProperty("SharingEnabled", true);
            body.addProperty("CrossTenantAllowed", true);
            postJsonWithAuth(MESS_BASE + "/tooling/edit_server_info", account.accessToken, body.toString(),
                    Map.of("api-version", "2.0"));
            extension.logger().info(LOG_PREFIX + "Server info configured for " + account.serverId);
        } catch (IOException e) {
            extension.logger().warning(LOG_PREFIX + "Could not update server info: " + e.getMessage());
        }
    }

    // ---- Host / Dehost / Update ----

    private void hostServer(ServerListAccount account) throws IOException {
        JsonObject transportInfo = new JsonObject();
        transportInfo.addProperty("ip", globalServerIp);
        JsonObject connectionInfo = new JsonObject();
        connectionInfo.addProperty("transportType", 0);
        connectionInfo.add("transportInfo", transportInfo);
        JsonObject body = new JsonObject();
        body.add("connectionInfo", connectionInfo);
        postJsonWithAuth(MESS_BASE + "/server/host", account.serverToken, body.toString());
    }

    private void dehostServer(ServerListAccount account) throws IOException {
        postEmptyWithAuth(MESS_BASE + "/server/dehost", account.serverToken);
        account.active = false;
    }

    private static final java.net.InetSocketAddress INTERNAL_ADDRESS = new java.net.InetSocketAddress("1.1.1.1", 0);

    private int getPlayerCount() {
        try {
            // Access internal Geyser API for accurate player count including Java players
            org.geysermc.geyser.GeyserImpl geyser = org.geysermc.geyser.GeyserImpl.getInstance();
            org.geysermc.geyser.ping.IGeyserPingPassthrough pingPassthrough = geyser.getBootstrap().getGeyserPingPassthrough();
            if (pingPassthrough != null) {
                org.geysermc.geyser.ping.GeyserPingInfo pingInfo = pingPassthrough.getPingInformation(INTERNAL_ADDRESS);
                if (pingInfo != null) {
                    return pingInfo.getPlayers().getOnline();
                }
            }
        } catch (Exception ignored) {
            // Fall back to extension API if internal access fails
        }
        return extension.geyserApi().onlineConnections().size();
    }

    private void sendServerUpdate(ServerListAccount account) {
        try {
            int playerCount = getPlayerCount();
            String json = "{\"playerCount\":" + playerCount
                    + ",\"maxPlayers\":" + globalMaxPlayers
                    + ",\"health\":" + MESS_HEALTH_OPTIMAL + "}";
            postJsonWithAuth(MESS_BASE + "/server/update", account.serverToken, json);
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Server update failed: " + e.getMessage());
        }
    }

    // ---- Scheduling (per account) ----

    private void scheduleServerUpdates(ServerListAccount account) {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> sendServerUpdate(account), 10, 10, TimeUnit.SECONDS);
        accountTasks.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(task);
    }

    private void scheduleTokenRefresh(ServerListAccount account) {
        String accountLabel = account.tenantId != null ? account.tenantId : account.serverId;
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean accessValid = account.accessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS
                        || refreshAccessToken(account);
                boolean eduValid = account.eduAccessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS
                        || refreshEduAccessToken(account);
                if (!accessValid || !eduValid) {
                    extension.logger().error(LOG_PREFIX + "Token refresh failed for account " + accountLabel +
                            ". Remove and re-add the account to re-authenticate.");
                    return;
                }
                fetchServerToken(account);
                saveAllAccounts();
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Token refresh failed for account " + accountLabel + ": " + e.getMessage());
            }
        }, 30, 30, TimeUnit.MINUTES);
        accountTasks.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(task);
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
                    source.sendMessage(LOG_PREFIX + "Usage: /edu serverlist remove <number>");
                    return;
                }
                try {
                    removeAccount(source, Integer.parseInt(args[1]));
                } catch (NumberFormatException e) {
                    source.sendMessage(LOG_PREFIX + "Invalid number: " + args[1]);
                }
            }
            default -> showStatus(source);
        }
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(LOG_PREFIX + "=== Server List Accounts ===");
        if (accounts.isEmpty()) {
            source.sendMessage("  No accounts registered. Use '/edu serverlist add' to add one.");
            return;
        }
        for (int i = 0; i < accounts.size(); i++) {
            ServerListAccount a = accounts.get(i);
            String tenant = a.tenantId != null ? a.tenantId : "unknown";
            String status = a.active ? "active" : "inactive";
            String expiry = "";
            if (a.serverTokenExpires > 0) {
                expiry = " (expires: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochSecond(a.serverTokenExpires)) + ")";
            }
            source.sendMessage("  #" + (i + 1) + " | tenant: " + tenant + " | server: " +
                    (a.serverId != null ? a.serverId : "none") + " | " + status + expiry);
        }
    }

    // ---- Config & Session Persistence ----

    private Path getConfigPath() {
        return extension.dataFolder().resolve(CONFIG_FILE);
    }

    private Path getSessionPath() {
        return extension.dataFolder().resolve(SESSION_FILE);
    }

    // Global config shared by all accounts
    private String globalServerName = "";
    private String globalServerIp = "";
    private int globalMaxPlayers = 40;

    private void loadGlobalConfig() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath,
                        "# EduGeyser Server List Configuration\n\n" +
                        "# Display name shown in the Education Edition server list.\n" +
                        "server-name: \"\"\n\n" +
                        "# Public IP:port for MESS registration (e.g. \"mc.example.com:19132\").\n" +
                        "# Leave empty to auto-detect.\n" +
                        "server-ip: \"\"\n\n" +
                        "# Maximum players shown in the server list.\n" +
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
            globalServerName = node.node("server-name").getString("");
            globalServerIp = node.node("server-ip").getString("");
            globalMaxPlayers = node.node("max-players").getInt(40);
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Failed to load config: " + e.getMessage());
        }
    }

    private void loadAllAccounts() {
        try {
            Files.createDirectories(extension.dataFolder());
        } catch (IOException e) {
            extension.logger().error(LOG_PREFIX + "Failed to create data folder: " + e.getMessage());
        }

        loadGlobalConfig();

        // Load account sessions
        Path sessionPath = getSessionPath();
        if (!Files.exists(sessionPath)) return;

        synchronized (configFileLock) {
            try {
                var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                        .path(sessionPath).build();
                var root = loader.load();
                var accountsNode = root.node("accounts");
                if (accountsNode.isList()) {
                    for (var node : accountsNode.childrenList()) {
                        ServerListAccount a = new ServerListAccount();
                        a.serverId = node.node("server-id").getString();
                        a.refreshToken = node.node("refresh-token").getString();
                        a.accessToken = node.node("access-token").getString();
                        a.accessTokenExpires = node.node("access-token-expires").getLong(0);
                        a.eduRefreshToken = node.node("edu-refresh-token").getString();
                        a.eduAccessToken = node.node("edu-access-token").getString();
                        a.eduAccessTokenExpires = node.node("edu-access-token-expires").getLong(0);
                        a.serverToken = node.node("server-token").getString();
                        a.serverTokenJwt = node.node("server-token-jwt").getString();
                        a.serverTokenExpires = node.node("server-token-expires").getLong(0);
                        a.extractTenantId();
                        accounts.add(a);
                    }
                }
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to load sessions: " + e.getMessage());
            }
        }
    }

    private void saveAllAccounts() {
        synchronized (configFileLock) {
            Path path = getSessionPath();
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("# EduGeyser Server List Sessions\n");
                sb.append("# Managed automatically. Do not edit.\n\n");
                sb.append("accounts:\n");
                for (ServerListAccount a : accounts) {
                    sb.append("  - server-id: ").append(yamlStr(a.serverId)).append("\n");
                    sb.append("    refresh-token: ").append(yamlStr(a.refreshToken)).append("\n");
                    sb.append("    access-token: ").append(yamlStr(a.accessToken)).append("\n");
                    sb.append("    access-token-expires: ").append(a.accessTokenExpires).append("\n");
                    sb.append("    edu-refresh-token: ").append(yamlStr(a.eduRefreshToken)).append("\n");
                    sb.append("    edu-access-token: ").append(yamlStr(a.eduAccessToken)).append("\n");
                    sb.append("    edu-access-token-expires: ").append(a.eduAccessTokenExpires).append("\n");
                    sb.append("    server-token: ").append(yamlStr(a.serverToken)).append("\n");
                    sb.append("    server-token-jwt: ").append(yamlStr(a.serverTokenJwt)).append("\n");
                    sb.append("    server-token-expires: ").append(a.serverTokenExpires).append("\n");
                }
                Files.writeString(path, sb.toString());
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to save sessions: " + e.getMessage());
            }
        }
    }

    private void clearAccountSession(ServerListAccount account) {
        account.refreshToken = null;
        account.accessToken = null;
        account.accessTokenExpires = 0;
        account.eduRefreshToken = null;
        account.eduAccessToken = null;
        account.eduAccessTokenExpires = 0;
        account.serverToken = null;
        account.serverTokenJwt = null;
        account.serverTokenExpires = 0;
        account.active = false;
        saveAllAccounts();
    }

    private static String formatIpPort(String ip, int port) {
        if (ip.contains(":")) {
            // IPv6 — wrap in brackets
            return "[" + ip + "]:" + port;
        }
        return ip + ":" + port;
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String yamlStr(@Nullable String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }

    // ---- IP Detection ----

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
                if (err.contains("authorization_pending")) {
                    throw new IOException("authorization_pending");
                }
                throw new IOException("HTTP " + code + ": " + err);
            }
            try (InputStreamReader isr = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(isr).getAsJsonObject();
            }
        } finally {
            con.disconnect();
        }
    }

    private String getWithAuth(String url, String bearerToken) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            int code = con.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + ": " + readStream(con.getErrorStream()));
            return readStream(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private void postJsonWithAuth(String url, String bearerToken, String jsonBody) throws IOException {
        postJsonWithAuth(url, bearerToken, jsonBody, Map.of());
    }

    private void postJsonWithAuth(String url, String bearerToken, String jsonBody, Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                con.setRequestProperty(h.getKey(), h.getValue());
            }
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + ": " + readStream(con.getErrorStream()));
        } finally {
            con.disconnect();
        }
    }

    private String postEmptyWithAuth(String url, String bearerToken) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(new byte[0]);
            }
            int code = con.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + ": " + readStream(con.getErrorStream()));
            return readStream(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private String readStream(@Nullable InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private long parseTokenExpiry(JsonObject response) {
        if (response.has("expires_on")) return response.get("expires_on").getAsLong();
        if (response.has("expires_in")) return Instant.now().getEpochSecond() + response.get("expires_in").getAsLong();
        extension.logger().warning(LOG_PREFIX + "Token response missing expires_on and expires_in. Assuming 1 hour expiry.");
        return Instant.now().getEpochSecond() + 3600;
    }
}
