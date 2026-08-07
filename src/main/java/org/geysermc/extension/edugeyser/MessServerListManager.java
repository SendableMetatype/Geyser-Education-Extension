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
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Manages multiple MESS server list registrations.
 * Each account represents a Global Admin M365 tenant whose server list
 * will show this server. Ported from EduGeyser's EducationAuthManager.
 */
public class MessServerListManager {

    private static final String MESS_BASE = "https://dedicatedserver.minecrafteduservices.com";
    private static final String CONFIG_FILE = "serverlist_config.yml";
    private static final String SESSION_FILE = "sessions_serverlist.yml";
    private static final String LOG_PREFIX = "[EduServerList] ";
    private static final String DEFAULT_SERVER_NAME = "Server Name";
    private static final int HTTP_TIMEOUT = 15000;
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 60;
    private static final long RESTORE_RETRY_INTERVAL_SECONDS = 100;
    // Dehosting at shutdown is a courtesy: MESS drops a server from the list on
    // its own once its updates stop. It therefore never gets more than a moment
    // of the server's shutdown, however slow or hung the remote side is.
    private static final long SHUTDOWN_QUIESCENCE_BUDGET_MILLIS = 1000;
    private static final long SHUTDOWN_DEHOST_BUDGET_MILLIS = 2000;
    private static final int SHUTDOWN_HTTP_TIMEOUT_MILLIS = 1500;
    private static final int MESS_HEALTH_OPTIMAL = 2;
    // The server list tile only changes visually with the player count, so updates are
    // sent on change. MESS quietly decays the DISPLAYED health of servers whose last
    // update is stale (Mid after a few minutes, Poor near five, offline at 1 hour), so
    // the keepalive must stay under the first decay step; 2 minutes holds full health.
    private static final long UPDATE_KEEPALIVE_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final String[] PUBLIC_IP_SERVICES = {
            "https://checkip.amazonaws.com",
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://ifconfig.me/ip",
            "https://ipv4.icanhazip.com",
            "https://ipinfo.io/ip"
    };

    private final EduGeyserExtension extension;
    private final ScheduledExecutorService scheduler;
    private final EntraOAuthClient oauthClient;
    private final Object configFileLock = new Object();
    private final List<ServerListAccount> accounts = new CopyOnWriteArrayList<>();
    private final Map<ServerListAccount, List<ScheduledFuture<?>>> accountTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<ServerListAccount, ScheduledFuture<?>> restoreRetryTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<ServerListAccount, ScheduledFuture<?>> deviceCodeRetryTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> authenticationFlows = ConcurrentHashMap.newKeySet();
    private final ShutdownBarrier shutdownBarrier = new ShutdownBarrier();
    private volatile boolean serverListEndpointAvailable = true;

    public MessServerListManager(EduGeyserExtension extension) {
        this.extension = extension;
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.oauthClient = new EntraOAuthClient(scheduler, shutdownBarrier::isShutdownRequested);
    }

    // ---- Lifecycle ----

    public void initialize() {
        loadAllAccounts();
        if (!serverListEndpointAvailable) {
            return;
        }

        if (!resolveServerListEndpoint()) {
            extension.logger().error(LOG_PREFIX + "Could not auto-detect a public server IP. " +
                    "Server list registration is disabled until server-ip is set in serverlist_config.yml.");
            return;
        }

        // Start auth flows for existing accounts
        for (int i = 0; i < accounts.size(); i++) {
            final int idx = i;
            ServerListAccount account = accounts.get(i);
            scheduler.execute(() -> runAuthFlow(account, idx));
        }
    }

    public void shutdown() {
        shutdownBarrier.requestShutdown();
        for (CompletableFuture<?> flow : authenticationFlows) {
            flow.cancel(false);
        }
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

        // An authentication/restoration flow may already be inside register or host.
        // Give those a moment to leave the barrier, then dehost exclusively so
        // nothing can advertise the server afterwards. Everything here is bounded:
        // a tile that does not get dehosted expires on its own.
        boolean dehosted = shutdownBarrier.afterQuiescence(this::dehostEverythingBriefly,
                SHUTDOWN_QUIESCENCE_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        if (!dehosted) {
            extension.logger().debug(LOG_PREFIX + "Remote work was still in flight at shutdown; " +
                    "the server list tiles will expire on their own.");
        }
        saveAllAccounts();

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                extension.logger().warning(LOG_PREFIX + "Some authentication tasks did not terminate within 5 seconds.");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- Auth Flow (per account) ----

    private void runAuthFlow(ServerListAccount account, int index) {
        ShutdownBarrier.Lease lease = shutdownBarrier.tryEnter();
        if (lease == null) {
            return;
        }
        try (lease) {
            try {
                restoreOrAuthenticate(account, index);
            } catch (InterruptedException e) {
                extension.logger().debug(LOG_PREFIX + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Auth flow failed for account #" + (index + 1) + ": " + e.getMessage());
            }
        }
    }

    /**
     * Completes the auth flow by registering/hosting the server.
     * Returns false when shutdown won the lifecycle race before completion.
     * Throws on other failures so callers can distinguish them from shutdown.
     */
    private boolean completeAuthFlow(ServerListAccount account, int index) throws Exception {
        ShutdownBarrier.Lease lease = shutdownBarrier.tryEnter();
        if (lease == null) {
            return false;
        }
        try (lease) {
            if (account.serverId != null && !account.serverId.isEmpty()) {
                fetchServerToken(account);
            } else {
                registerNewServer(account);
                extension.logger().debug(LOG_PREFIX + "Account #" + (index + 1) + " registered with server ID: " + account.serverId);
            }

            if (shutdownBarrier.isShutdownRequested()) return false;
            tryEditTenantSettings(account);
            if (shutdownBarrier.isShutdownRequested()) return false;
            hostServer(account);
            if (shutdownBarrier.isShutdownRequested()) return false;
            tryEditServerInfo(account);
            account.extractTenantId();
            account.active = true;
            saveAllAccounts();

            extension.logger().info(LOG_PREFIX + "Account #" + (index + 1) + " hosted at " + globalServerIp +
                    " (" + account.displayLabel() + ")");

            if (shutdownBarrier.isShutdownRequested()) return false;
            scheduleServerUpdates(account);
            scheduleTokenRefresh(account);
            return true;
        }
    }

    private void restoreOrAuthenticate(ServerListAccount account, int index) throws IOException, InterruptedException {
        if (shutdownBarrier.isShutdownRequested()) {
            return;
        }
        boolean hasTooling = account.refreshToken != null && !account.refreshToken.isEmpty();
        boolean hasEdu = account.eduRefreshToken != null && !account.eduRefreshToken.isEmpty();

        if (hasTooling && hasEdu) {
            // A complete token pair is worth restoring even without a saved
            // serverId (auth succeeded but registration never completed):
            // completeAuthFlow registers a new server in that case. Sessions
            // are only discarded when Entra definitively rejects a refresh
            // token; transient failures (network, 5xx, the recurring MESS
            // 504s) keep both Global Admin sign ins and retry, since re-auth
            // here costs two device code flows.
            try {
                ensureValidAuthenticationPair(account);
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
            extension.logger().debug(LOG_PREFIX + "Restoring account #" + (index + 1) + " (" + account.displayLabel() + ")");
            try {
                completeAuthFlow(account, index);
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to restore the server list registration for account #" +
                        (index + 1) + ": " + e.getMessage() + " (sign in kept; retrying automatically)");
                scheduleRestoreRetry(account, index);
            }
            return;
        }

        if (hasTooling || hasEdu) {
            // Unlike a missing serverId, half a token pair is genuinely
            // unusable: both sign ins are required, so a fresh device code
            // flow is the only way forward.
            extension.logger().debug(LOG_PREFIX + "Partial session for account #" + (index + 1) + ", re-authenticating...");
            clearAccountSession(account);
        }

        startDeviceCodeFlows(account, index);
    }

    /**
     * Runs both device code flows for an unattended context (startup restore
     * or forced reauthentication; the /edu serverlist add command has its own
     * handling). Every terminal failure schedules its own recovery, since no
     * other task exists for the account at this point and the alternative is
     * an account that sits idle until a restart.
     */
    private void startDeviceCodeFlows(ServerListAccount account, int index) {
        cancelDeviceCodeRetry(account);
        if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
            return;
        }
        CompletableFuture<Void> authenticationFlow = trackAuthenticationFlow(doDeviceCodeFlows(account, index));
        authenticationFlow.thenRun(() -> {
            // The sign ins can complete long after they were requested; the
            // account may have been removed or the server stopped since.
            if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
                return;
            }
            try {
                if (!completeAuthFlow(account, index)) {
                    return;
                }
            } catch (Exception e) {
                // Signed in but hosting failed: infrastructure. The account
                // holds fresh refresh tokens again, so the restore retry loop
                // takes it from here.
                extension.logger().error(LOG_PREFIX + "Failed to host the server list registration for account #" +
                        (index + 1) + ": " + e.getMessage() + " (sign in kept; retrying automatically)");
                scheduleRestoreRetry(account, index);
            }
        }).exceptionally(ex -> {
            // The sign in never completed (typically an expired device code)
            // or the device code request itself failed. Prompt again.
            if (shutdownBarrier.isShutdownRequested()) {
                return null;
            }
            extension.logger().error(LOG_PREFIX + "Auth failed for account #" + (index + 1) + ": " + ex.getMessage());
            scheduleDeviceCodeRetry(account, index);
            return null;
        });
    }

    private void scheduleDeviceCodeRetry(ServerListAccount account, int index) {
        if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
            return;
        }
        deviceCodeRetryTasks.computeIfAbsent(account, ignored -> scheduler.schedule(() -> {
            deviceCodeRetryTasks.remove(account);
            startDeviceCodeFlows(account, index);
        }, RESTORE_RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS));
        if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
            cancelDeviceCodeRetry(account);
        }
    }

    private void cancelDeviceCodeRetry(ServerListAccount account) {
        ScheduledFuture<?> task = deviceCodeRetryTasks.remove(account);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void scheduleRestoreRetry(ServerListAccount account, int index) {
        if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
            return;
        }
        restoreRetryTasks.computeIfAbsent(account, ignored -> scheduler.scheduleWithFixedDelay(
                () -> retryRestore(account, index),
                RESTORE_RETRY_INTERVAL_SECONDS,
                RESTORE_RETRY_INTERVAL_SECONDS,
                TimeUnit.SECONDS));
    }

    private void retryRestore(ServerListAccount account, int index) {
        if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
            cancelRestoreRetry(account);
            return;
        }
        try {
            try {
                ensureValidAuthenticationPair(account);
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
            if (completeAuthFlow(account, index)) {
                cancelRestoreRetry(account);
            }
        } catch (Exception e) {
            extension.logger().warning(LOG_PREFIX + "Server list restore retry failed for account #" + (index + 1) +
                    ": " + e.getMessage() + " (retrying in " + RESTORE_RETRY_INTERVAL_SECONDS + " seconds)");
        }
    }

    private void cancelRestoreRetry(ServerListAccount account) {
        ScheduledFuture<?> task = restoreRetryTasks.remove(account);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Whether this account pair requires fresh interactive sign-ins. */
    private static boolean isLoginRejected(Exception e) {
        return e instanceof TenantValidationException
                || EntraOAuthClient.requiresInteractiveLogin(e);
    }

    /**
     * A saved refresh token was definitively rejected; only fresh device code
     * sign ins can revive the account. Cancels the account's scheduled work
     * first so the eventual completeAuthFlow schedules its tasks exactly
     * once. The serverId survives clearAccountSession, so the existing MESS
     * registration is reused after the re-auth.
     */
    private void reauthenticate(ServerListAccount account, int index) {
        cancelRestoreRetry(account);
        cancelDeviceCodeRetry(account);
        cancelAccountTasks(account);
        clearAccountSession(account);
        startDeviceCodeFlows(account, index);
    }

    private void cancelAccountTasks(ServerListAccount account) {
        List<ScheduledFuture<?>> tasks = accountTasks.remove(account);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) task.cancel(false);
        }
    }

    // ---- Add Account (command-triggered) ----

    public void addAccount(CommandSource source) {
        if (shutdownBarrier.isShutdownRequested()) {
            source.sendMessage(LOG_PREFIX + "Server list registration is shutting down.");
            return;
        }
        if (!serverListEndpointAvailable) {
            source.sendMessage(LOG_PREFIX + "Server list registration is disabled until server-ip is set in serverlist_config.yml.");
            return;
        }

        ServerListAccount account = new ServerListAccount();
        int index = accounts.size();
        accounts.add(account);
        source.sendMessage(LOG_PREFIX + "Starting device code flow for new account #" + (index + 1) + "...");

        scheduler.execute(() -> {
            if (shutdownBarrier.isShutdownRequested()) {
                accounts.remove(account);
                return;
            }
            CompletableFuture<Void> authenticationFlow = trackAuthenticationFlow(doDeviceCodeFlows(account, index));
            authenticationFlow.thenRun(() -> {
                // Same guard as the unattended flow: the account may have
                // been removed or the server stopped during the sign in.
                if (shutdownBarrier.isShutdownRequested() || !accounts.contains(account)) {
                    return;
                }
                try {
                    if (!completeAuthFlow(account, index)) {
                        return;
                    }
                    source.sendMessage(LOG_PREFIX + "Account #" + (index + 1) + " registered successfully!" +
                            " Tenant: " + account.displayLabel());
                } catch (Exception e) {
                    extension.logger().error(LOG_PREFIX + "Failed to host server: " + e.getMessage());
                    accounts.remove(account);
                    source.sendMessage(LOG_PREFIX + "Failed to host server: " + e.getMessage());
                }
            }).exceptionally(ex -> {
                if (shutdownBarrier.isShutdownRequested()) {
                    return null;
                }
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

        cancelRestoreRetry(account);
        cancelDeviceCodeRetry(account);
        cancelAccountTasks(account);

        accounts.remove(account);
        saveAllAccounts();
        source.sendMessage(LOG_PREFIX + "Removed account #" + number +
                (" (" + account.displayLabel() + ")"));

        // The remote cleanup can block on network timeouts, so it runs off the command thread.
        scheduler.execute(() -> {
            if (account.serverToken != null) {
                try {
                    dehostServer(account);
                } catch (Exception e) {
                    extension.logger().warning(LOG_PREFIX + "Could not dehost: " + e.getMessage());
                }
            }

            // The serverId only exists in our session file, so removing the account would
            // orphan the registration as a dead tile in the tenant's server list forever.
            // Best effort: delete it; the account is already removed locally regardless.
            if (account.serverId != null && !account.serverId.isEmpty()) {
                try {
                    ensureValidAccessToken(account);
                    JsonObject body = new JsonObject();
                    body.addProperty("ServerId", account.serverId);
                    postJsonWithAuth(MESS_BASE + "/tooling/delete_server_registration", account.accessToken, body.toString());
                    extension.logger().info(LOG_PREFIX + "Deleted server registration " + account.serverId);
                } catch (Exception e) {
                    extension.logger().warning(LOG_PREFIX + "Could not delete server registration "
                            + account.serverId + ": " + e.getMessage());
                }
            }
        });
    }

    // ---- Device Code OAuth ----

    private <T> CompletableFuture<T> trackAuthenticationFlow(CompletableFuture<T> flow) {
        authenticationFlows.add(flow);
        flow.whenComplete((ignored, throwable) -> authenticationFlows.remove(flow));
        if (shutdownBarrier.isShutdownRequested()) {
            flow.cancel(false);
        }
        return flow;
    }

    private CompletableFuture<Void> doDeviceCodeFlows(ServerListAccount account, int index) {
        extension.logger().debug(LOG_PREFIX + "Account #" + (index + 1) + ": Two sign-ins required.");
        return doDeviceCodeFlow(EntraOAuthClient.TOOLING_CLIENT_ID, "tooling authentication")
                .thenCompose(toolingTokens -> {
            account.accessToken = toolingTokens.accessToken();
            account.refreshToken = toolingTokens.refreshToken();
            account.accessTokenExpires = toolingTokens.accessTokenExpires();
            account.extractTokenClaims();

            extension.logger().debug(LOG_PREFIX + "Step 2/2: Sign in for server registration...");
            return doDeviceCodeFlow(EntraOAuthClient.EDUCATION_CLIENT_ID, "server authentication");
        }).thenAccept(eduTokens -> {
            String eduAccessToken = eduTokens.accessToken();
            validateMatchingTenants(account.accessToken, eduAccessToken);
            account.eduAccessToken = eduAccessToken;
            account.eduRefreshToken = eduTokens.refreshToken();
            account.eduAccessTokenExpires = eduTokens.accessTokenExpires();
            account.extractTokenClaims();
            extension.logger().debug(LOG_PREFIX + "Both authentications successful (" + account.displayLabel() + ")!");
        });
    }

    private CompletableFuture<EntraOAuthClient.Tokens> doDeviceCodeFlow(String clientId, String label) {
        try {
            EntraOAuthClient.DeviceAuthorization authorization = oauthClient.requestDeviceCode(clientId);

            extension.logger().info(LOG_PREFIX + "============================================");
            extension.logger().info(LOG_PREFIX + "  Go to: " + authorization.verificationUri());
            extension.logger().info(LOG_PREFIX + "  Enter code: " + authorization.userCode());
            extension.logger().info(LOG_PREFIX + "  (" + label + ")");
            extension.logger().info(LOG_PREFIX + "============================================");
            extension.logger().debug(LOG_PREFIX + "Waiting for sign-in...");

            return oauthClient.poll(authorization).thenApply(tokens -> {
                extension.logger().debug(LOG_PREFIX + "Authentication successful (" + label + ")!");
                return tokens;
            });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---- Token Refresh ----

    private void refreshAccessToken(ServerListAccount account) throws IOException {
        if (account.refreshToken == null) {
            throw new IOException("No tooling refresh token available");
        }
        EntraOAuthClient.Tokens tokens = oauthClient.refresh(
                EntraOAuthClient.TOOLING_CLIENT_ID, account.refreshToken);
        account.accessToken = tokens.accessToken();
        account.refreshToken = tokens.refreshToken();
        account.accessTokenExpires = tokens.accessTokenExpires();
        account.extractTokenClaims();
        saveAllAccounts();
    }

    private void refreshEduAccessToken(ServerListAccount account) throws IOException {
        if (account.eduRefreshToken == null) {
            throw new IOException("No edu refresh token available");
        }
        EntraOAuthClient.Tokens tokens = oauthClient.refresh(
                EntraOAuthClient.EDUCATION_CLIENT_ID, account.eduRefreshToken);
        account.eduAccessToken = tokens.accessToken();
        account.eduRefreshToken = tokens.refreshToken();
        account.eduAccessTokenExpires = tokens.accessTokenExpires();
        account.extractTokenClaims();
        saveAllAccounts();
    }

    private void ensureValidAccessToken(ServerListAccount account) throws IOException {
        if (account.accessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS) return;
        refreshAccessToken(account);
    }

    private void ensureValidEduAccessToken(ServerListAccount account) throws IOException {
        if (account.eduAccessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS) return;
        refreshEduAccessToken(account);
    }

    private void ensureValidAuthenticationPair(ServerListAccount account) throws IOException {
        ensureValidAccessToken(account);
        ensureValidEduAccessToken(account);
        validateMatchingTenants(account.accessToken, account.eduAccessToken);
    }

    static void validateMatchingTenants(@Nullable String toolingAccessToken, @Nullable String eduAccessToken) {
        String toolingTenantId = ServerListAccount.extractTenantIdFromToken(toolingAccessToken);
        String eduTenantId = ServerListAccount.extractTenantIdFromToken(eduAccessToken);
        if (toolingTenantId == null) {
            throw new TenantValidationException("The tooling sign-in token does not contain a tenant ID.");
        }
        if (eduTenantId == null) {
            throw new TenantValidationException("The server sign-in token does not contain a tenant ID.");
        }
        if (!toolingTenantId.equalsIgnoreCase(eduTenantId)) {
            throw new TenantValidationException("The two sign-ins belong to different tenants (tooling: "
                    + toolingTenantId + ", server: " + eduTenantId + "). Use accounts from the same tenant.");
        }
    }

    private static final class TenantValidationException extends IllegalStateException {
        private TenantValidationException(String message) {
            super(message);
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
            extension.logger().debug(LOG_PREFIX + "Tenant settings configured: dedicated servers enabled, teacher access, cross-tenant.");
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
            extension.logger().debug(LOG_PREFIX + "Server info configured for " + account.serverId);
        } catch (IOException e) {
            extension.logger().warning(LOG_PREFIX + "Could not update server info: " + e.getMessage());
        }
    }

    // ---- Host / Dehost / Update ----

    private void hostServer(ServerListAccount account) throws IOException {
        if (globalServerIp == null || globalServerIp.isEmpty()) {
            throw new IOException("No server list endpoint configured or detected. Set server-ip in serverlist_config.yml.");
        }

        JsonObject transportInfo = new JsonObject();
        transportInfo.addProperty("ip", globalServerIp);
        JsonObject connectionInfo = new JsonObject();
        connectionInfo.addProperty("transportType", 0);
        connectionInfo.add("transportInfo", transportInfo);
        JsonObject body = new JsonObject();
        body.add("connectionInfo", connectionInfo);
        postJsonWithAuth(MESS_BASE + "/server/host", account.serverToken, body.toString());
        // Hosting resets MESS's view of the server, so force a fresh update on the next tick.
        account.lastSentPlayerCount = -1;
    }

    private void dehostServer(ServerListAccount account) throws IOException {
        dehostServer(account, HTTP_TIMEOUT);
    }

    private void dehostServer(ServerListAccount account, int timeoutMillis) throws IOException {
        postEmptyWithAuth(MESS_BASE + "/server/dehost", account.serverToken, timeoutMillis);
        account.active = false;
    }

    /**
     * Best-effort dehost of every hosted account within a fixed budget, for the
     * shutdown path. Accounts left over when the budget runs out keep their
     * registration; MESS stops listing them once their updates stop arriving.
     */
    private void dehostEverythingBriefly() {
        long deadline = System.currentTimeMillis() + SHUTDOWN_DEHOST_BUDGET_MILLIS;
        for (ServerListAccount account : accounts) {
            if (account.serverToken == null) {
                continue;
            }
            if (System.currentTimeMillis() >= deadline) {
                extension.logger().debug(LOG_PREFIX + "Out of time dehosting at shutdown; " +
                        "the remaining server list tiles will expire on their own.");
                return;
            }
            try {
                dehostServer(account, SHUTDOWN_HTTP_TIMEOUT_MILLIS);
            } catch (Exception e) {
                extension.logger().debug(LOG_PREFIX + "Could not dehost account " + account.displayLabel() +
                        " at shutdown: " + e.getMessage());
            }
        }
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
        ShutdownBarrier.Lease lease = shutdownBarrier.tryEnter();
        if (lease == null) {
            return;
        }
        try (lease) {
            try {
                int playerCount = reportedPlayerCount(getPlayerCount(), globalMaxPlayers);
                long now = System.currentTimeMillis();
                if (playerCount == account.lastSentPlayerCount
                        && now - account.lastSuccessfulUpdateMillis < UPDATE_KEEPALIVE_MILLIS) {
                    return;
                }
                String json = "{\"playerCount\":" + playerCount
                        + ",\"maxPlayers\":" + globalMaxPlayers
                        + ",\"health\":" + MESS_HEALTH_OPTIMAL + "}";
                postJsonWithAuth(MESS_BASE + "/server/update", account.serverToken, json);
                account.lastSentPlayerCount = playerCount;
                account.lastSuccessfulUpdateMillis = now;
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Server update failed: " + e.getMessage());
            }
        }
    }

    // ---- Scheduling (per account) ----

    /**
     * The player count reported to MESS. Unlike join codes the server list
     * actually displays count and max, so the maximum cannot be hardcoded
     * away, but capacity enforcement still belongs to the backend server.
     * The report is therefore clamped to at least one below the maximum, so
     * the tile always stays joinable and the API never sees a count above
     * the max it would reject. The loader guarantees a maximum of at least
     * one, so the result is never negative.
     */
    static int reportedPlayerCount(int actualCount, int maxPlayers) {
        return Math.min(actualCount, maxPlayers - 1);
    }

    private void scheduleServerUpdates(ServerListAccount account) {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> sendServerUpdate(account), 10, 10, TimeUnit.SECONDS);
        accountTasks.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(task);
    }

    private void scheduleTokenRefresh(ServerListAccount account) {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            ShutdownBarrier.Lease lease = shutdownBarrier.tryEnter();
            if (lease == null) {
                return;
            }
            try (lease) {
                try {
                    try {
                        ensureValidAuthenticationPair(account);
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
                    if (shutdownBarrier.isShutdownRequested()) return;
                    fetchServerToken(account);
                    saveAllAccounts();
                } catch (Exception e) {
                    extension.logger().error(LOG_PREFIX + "Token refresh failed for account " + account.displayLabel() +
                            ": " + e.getMessage() + " (retried on the next cycle)");
                }
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
            String tenant = a.displayLabel();
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
    private String globalServerName = DEFAULT_SERVER_NAME;
    private String globalServerIp = "";
    private int globalServerPort = -1;
    private int globalMaxPlayers = 40;
    private boolean globalServerIpConfigured;
    private boolean globalServerPortConfigured;

    private void loadGlobalConfig() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath,
                        "# EduGeyser Server List Configuration\n\n" +
                        "# Display name shown in the Education Edition server list.\n" +
                        "server-name: \"" + DEFAULT_SERVER_NAME + "\"\n\n" +
                        "# Public IP or hostname (e.g. \"mc.example.com\").\n" +
                        "# Leave empty to auto-detect.\n" +
                        "server-ip: \"\"\n\n" +
                        "# Port players connect with. Leave empty to use Geyser's port.\n" +
                        "# Only set this if the external port differs from Geyser's (e.g. when using playit.gg).\n" +
                        "server-port: \"\"\n\n" +
                        "# Maximum players shown in the server list. The shown player count is\n" +
                        "# capped just below this so the server always stays joinable; enforce\n" +
                        "# real player limits in the backend server software.\n" +
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
            String configuredServerName = node.node("server-name").getString(DEFAULT_SERVER_NAME);
            globalServerName = configuredServerName == null || configuredServerName.isBlank()
                    ? DEFAULT_SERVER_NAME : configuredServerName;
            String configuredIp = node.node("server-ip").getString("");
            globalServerIp = configuredIp == null ? "" : configuredIp.trim();
            globalServerIpConfigured = !globalServerIp.isEmpty();
            String configuredPort = node.node("server-port").getString("");
            String portStr = configuredPort == null ? "" : configuredPort.trim();
            try {
                globalServerPort = parseServerPort(portStr);
            } catch (IllegalArgumentException e) {
                serverListEndpointAvailable = false;
                extension.logger().error(LOG_PREFIX + "Invalid server-port in " + CONFIG_FILE + ": " +
                        e.getMessage() + " Server list registration is disabled until this value is fixed and the server restarted.");
                return;
            }
            globalServerPortConfigured = !portStr.isEmpty();
            int configuredMaxPlayers = node.node("max-players").getInt(40);
            if (configuredMaxPlayers < 1) {
                extension.logger().warning(LOG_PREFIX + "max-players must be at least 1; using 1 instead of "
                        + configuredMaxPlayers + ".");
                configuredMaxPlayers = 1;
            }
            globalMaxPlayers = configuredMaxPlayers;
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
                accounts.addAll(readAccounts(sessionPath, (entry, error) ->
                        extension.logger().error(LOG_PREFIX + "Skipping malformed session account #" + entry
                                + ": " + error.getMessage())));
            } catch (Exception e) {
                extension.logger().error(LOG_PREFIX + "Failed to load sessions: " + e.getMessage());
            }
        }
    }

    static List<ServerListAccount> readAccounts(Path sessionPath,
                                                 BiConsumer<Integer, Exception> malformedEntryHandler) throws IOException {
        var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                .path(sessionPath).build();
        var accountsNode = loader.load().node("accounts");
        List<ServerListAccount> loadedAccounts = new ArrayList<>();
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
                ServerListAccount account = new ServerListAccount();
                account.serverId = node.node("server-id").getString();
                account.refreshToken = node.node("refresh-token").getString();
                account.accessToken = node.node("access-token").getString();
                account.accessTokenExpires = node.node("access-token-expires").getLong(0);
                account.eduRefreshToken = node.node("edu-refresh-token").getString();
                account.eduAccessToken = node.node("edu-access-token").getString();
                account.eduAccessTokenExpires = node.node("edu-access-token-expires").getLong(0);
                account.serverToken = node.node("server-token").getString();
                account.serverTokenJwt = node.node("server-token-jwt").getString();
                account.serverTokenExpires = node.node("server-token-expires").getLong(0);
                account.extractTenantId();
                account.extractTokenClaims();
                loadedAccounts.add(account);
            } catch (Exception e) {
                malformedEntryHandler.accept(entry, e);
            }
        }
        return loadedAccounts;
    }

    private boolean resolveServerListEndpoint() {
        String host = globalServerIp == null ? "" : globalServerIp.trim();
        if (host.isEmpty()) {
            host = detectPublicIp();
            if (host == null) {
                serverListEndpointAvailable = false;
                globalServerIp = "";
                return false;
            }
            globalServerIp = host;
        }

        int port = globalServerPort > 0 ? globalServerPort : extension.geyserApi().bedrockListener().port();
        globalServerIp = formatIpPort(host, port);
        serverListEndpointAvailable = true;

        if (globalServerIpConfigured && globalServerPortConfigured) {
            extension.logger().info(LOG_PREFIX + "Using configured server list endpoint: " + globalServerIp);
        } else {
            extension.logger().warning(LOG_PREFIX + "Using inferred server list endpoint: " + globalServerIp +
                    ". This probably works, but may cause connection issues if the public address or external port differs. " +
                    "Set server-ip and server-port in serverlist_config.yml for reliable server list registration.");
        }
        return true;
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
                AtomicFileWriter.writeString(path, sb.toString());
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
        // Keep the registration token until completeAuthFlow replaces it. It
        // remains usable for dehosting if reauthentication is still pending
        // when the account is removed or the server shuts down.
        account.active = false;
        saveAllAccounts();
    }

    static String formatIpPort(String ip, int port) {
        String host = ip;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains(":")) {
            // IPv6 requires exactly one bracket pair when followed by a port.
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    static int parseServerPort(@Nullable String configuredPort) {
        String value = configuredPort == null ? "" : configuredPort.trim();
        if (value.isEmpty()) {
            return -1;
        }

        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a whole number from 1 through 65535, but got \"" + value + "\"", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("expected a value from 1 through 65535, but got " + port);
        }
        return port;
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
        for (String service : PUBLIC_IP_SERVICES) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) URI.create(service).toURL().openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                if (con.getResponseCode() == 200) {
                    String ip = readStream(con.getInputStream()).trim();
                    if (isValidDetectedIp(ip)) return ip;
                }
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
            }
        }
        return null;
    }

    private static boolean isValidDetectedIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45 || ip.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()
                    && !address.isSiteLocalAddress()
                    && !address.isMulticastAddress();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- HTTP Helpers ----

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
        return postEmptyWithAuth(url, bearerToken, HTTP_TIMEOUT);
    }

    private String postEmptyWithAuth(String url, String bearerToken, int timeoutMillis) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            con.setConnectTimeout(timeoutMillis);
            con.setReadTimeout(timeoutMillis);
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

}
