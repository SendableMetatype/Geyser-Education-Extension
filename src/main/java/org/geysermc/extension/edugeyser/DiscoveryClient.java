package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Client for Education Edition's Discovery API.
 * Handles join code registration, heartbeats, and dehosting.
 */
public class DiscoveryClient {

    private static final String DISCOVERY_BASE = "https://discovery.minecrafteduservices.com";
    // As captured from a real 26.x client's /host request.
    private static final int BUILD_NUMBER = 12632000;
    // Discovery may reject joins when playerCount reaches maxPlayers. EduGeyser
    // deliberately leaves authoritative capacity enforcement to the backend,
    // so keep the initial reported count (1) safely below this protocol value.
    private static final int HOST_MAX_PLAYERS = 40;
    private static final int HTTP_TIMEOUT = 15000;

    /**
     * Outcome of a heartbeat, classified so callers can tell a dead registration
     * from a transient failure. A registration is dead when the API rejects our
     * credentials (401/403, the serverToken expires 10 days after issue) or no
     * longer knows the host (404). Auth is checked before host existence, so an
     * expired token reports 401 forever and the 404 underneath is never visible.
     * Anything else (timeouts, 5xx) is transient and worth retrying as is.
     */
    public enum HeartbeatResult {
        OK,
        REGISTRATION_DEAD,
        TRANSIENT
    }

    /** IOException carrying the HTTP status code so callers can classify failures. */
    public static class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // Join code symbol names (index 0-17)
    private static final String[] CODE_SYMBOLS = {
        "Book", "Balloon", "Rail", "Alex", "Cookie", "Fish", "Agent", "Cake", "Pickaxe",
        "WaterBucket", "Steve", "Apple", "Carrot", "Panda", "Sign", "Potion", "Map", "Llama"
    };

    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Consumer<String> errorLogger;
    private final String msAccessToken;
    private final Transport transport;

    private volatile @Nullable String serverToken;
    private volatile @Nullable String passcode;

    public DiscoveryClient(ExtensionLogger logger, String msAccessToken) {
        this(logger::info, logger::warning, logger::error, msAccessToken, DiscoveryClient::post);
    }

    DiscoveryClient(Consumer<String> infoLogger, Consumer<String> warningLogger,
                    Consumer<String> errorLogger, String msAccessToken, Transport transport) {
        this.infoLogger = infoLogger;
        this.warningLogger = warningLogger;
        this.errorLogger = errorLogger;
        this.msAccessToken = msAccessToken;
        this.transport = transport;
    }

    /**
     * Register with Discovery to get a join code.
     * @param nethernetId the Nethernet server ID
     * @param serverName display name for the world
     * @param serverDetails host username
     * @return the parsed join code string (e.g. "Book, Balloon, Rail, Alex"), or null on failure
     */
    public @Nullable String host(String nethernetId, String serverName, String serverDetails) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("maxPlayers", HOST_MAX_PLAYERS);
            body.addProperty("networkId", nethernetId);
            body.addProperty("playerCount", 1);
            body.addProperty("protocolVersion", 1);
            body.addProperty("serverDetails", serverDetails);
            body.addProperty("serverName", serverName);
            body.addProperty("transportType", 2);

            JsonObject response = transport.post(request(
                    DISCOVERY_BASE + "/host", msAccessToken, body.toString(), true));

            if (response == null || !response.has("serverToken") || !response.has("passcode")) {
                errorLogger.accept("[Discovery] /host response missing serverToken or passcode");
                return null;
            }

            this.serverToken = response.get("serverToken").getAsString();
            this.passcode = response.get("passcode").getAsString();

            return parseJoinCode(passcode);
        } catch (IOException e) {
            errorLogger.accept("[Discovery] Failed to host: " + e.getMessage());
            return null;
        }
    }

    /**
     * Send a heartbeat to keep the join code alive.
     * Should be called every 100 seconds.
     */
    public HeartbeatResult heartbeat() {
        if (serverToken == null || passcode == null) return HeartbeatResult.TRANSIENT;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("passcode", passcode);
            body.addProperty("protocolVersion", 1);
            body.addProperty("transportType", 2);

            transport.post(request(DISCOVERY_BASE + "/heartbeat", serverToken, body.toString(), false));
            return HeartbeatResult.OK;
        } catch (HttpStatusException e) {
            errorLogger.accept("[Discovery] Heartbeat failed: " + e.getMessage());
            if (e.status == 401 || e.status == 403 || e.status == 404) {
                return HeartbeatResult.REGISTRATION_DEAD;
            }
            return HeartbeatResult.TRANSIENT;
        } catch (IOException e) {
            errorLogger.accept("[Discovery] Heartbeat failed: " + e.getMessage());
            return HeartbeatResult.TRANSIENT;
        }
    }

    /**
     * Update world metadata on Discovery.
     */
    public void update(String serverName, String serverDetails, int playerCount, int maxPlayers) {
        if (serverToken == null || passcode == null) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("maxPlayers", maxPlayers);
            body.addProperty("passcode", passcode);
            body.addProperty("playerCount", playerCount);
            body.addProperty("protocolVersion", 1);
            body.addProperty("serverDetails", serverDetails);
            body.addProperty("serverName", serverName);

            transport.post(request(DISCOVERY_BASE + "/update", serverToken, body.toString(), false));
        } catch (IOException e) {
            errorLogger.accept("[Discovery] Update failed: " + e.getMessage());
        }
    }

    /**
     * Remove the join code from Discovery.
     */
    public void dehost() {
        if (serverToken == null || passcode == null) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("passcode", passcode);
            body.addProperty("protocolVersion", 1);

            transport.post(request(DISCOVERY_BASE + "/dehost", serverToken, body.toString(), false));
            infoLogger.accept("[Discovery] Dehosted successfully");
        } catch (IOException e) {
            warningLogger.accept("[Discovery] Dehost failed: " + e.getMessage());
        }
    }

    public @Nullable String getServerToken() { return serverToken; }
    public @Nullable String getPasscode() { return passcode; }

    public void setServerToken(String token) { this.serverToken = token; }
    public void setPasscode(String passcode) { this.passcode = passcode; }

    // ---- Join Code Parsing ----

    /**
     * Converts a passcode like "10,13,5,1,17" to "Steve, Panda, Fish, Balloon, Llama"
     */
    public static String parseJoinCode(String passcode) {
        String[] parts = passcode.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int idx;
            try {
                idx = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid join-code symbol at position " + (i + 1), e);
            }
            if (idx < 0 || idx >= CODE_SYMBOLS.length) {
                throw new IllegalArgumentException("Join-code symbol out of range at position " + (i + 1) + ": " + idx);
            }
            if (i > 0) sb.append(", ");
            sb.append(CODE_SYMBOLS[idx]);
        }
        return sb.toString();
    }

    /**
     * Creates a share link from a passcode.
     * Format: https://education.minecraft.net/joinworld/{base64(passcode)}
     */
    public static String createShareLink(String passcode) {
        String encoded = Base64.getEncoder().encodeToString(passcode.getBytes(StandardCharsets.UTF_8));
        return "https://education.minecraft.net/joinworld/" + encoded;
    }

    // ---- HTTP Transport ----

    private static DiscoveryRequest request(String url, String bearerToken,
                                            String jsonBody, boolean expectsJsonResponse) {
        return new DiscoveryRequest(url, Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + bearerToken,
                "api-version", "2.0",
                "User-Agent", "libhttpclient/1.0.0.0"
        ), jsonBody, expectsJsonResponse);
    }

    private static @Nullable JsonObject post(DiscoveryRequest request) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(request.url()).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                con.setRequestProperty(header.getKey(), header.getValue());
            }
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(request.jsonBody().getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String err = readStream(con.getErrorStream());
                throw new HttpStatusException(code, "HTTP " + code + ": " + err);
            }
            if (request.expectsJsonResponse()) {
                try (InputStreamReader isr = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(isr).getAsJsonObject();
                }
            }
            return null;
        } finally {
            con.disconnect();
        }
    }

    private static String readStream(@Nullable InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    record DiscoveryRequest(String url, Map<String, String> headers,
                            String jsonBody, boolean expectsJsonResponse) {
    }

    @FunctionalInterface
    interface Transport {
        @Nullable JsonObject post(DiscoveryRequest request) throws IOException;
    }
}
