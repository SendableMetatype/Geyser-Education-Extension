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

/**
 * Client for Education Edition's Discovery API.
 * Handles join code registration, heartbeats, and dehosting.
 */
public class DiscoveryClient {

    private static final String DISCOVERY_BASE = "https://discovery.minecrafteduservices.com";
    private static final int BUILD_NUMBER = 12232001;
    private static final int HTTP_TIMEOUT = 15000;

    // Join code symbol names (index 0-17)
    private static final String[] CODE_SYMBOLS = {
        "Book", "Balloon", "Rail", "Alex", "Cookie", "Fish", "Agent", "Cake", "Pickaxe",
        "WaterBucket", "Steve", "Apple", "Carrot", "Panda", "Sign", "Potion", "Map", "Llama"
    };

    private final ExtensionLogger logger;
    private final String msAccessToken;

    private volatile @Nullable String serverToken;
    private volatile @Nullable String passcode;

    public DiscoveryClient(ExtensionLogger logger, String msAccessToken) {
        this.logger = logger;
        this.msAccessToken = msAccessToken;
    }

    /**
     * Register with Discovery to get a join code.
     * @param nethernetId the Nethernet server ID
     * @param serverName display name for the world
     * @param serverDetails host username
     * @param maxPlayers max player count
     * @return the parsed join code string (e.g. "Book, Balloon, Rail, Alex"), or null on failure
     */
    public @Nullable String host(String nethernetId, String serverName, String serverDetails, int maxPlayers) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("maxPlayers", maxPlayers);
            body.addProperty("networkId", nethernetId);
            body.addProperty("playerCount", 1);
            body.addProperty("protocolVersion", 1);
            body.addProperty("serverDetails", serverDetails);
            body.addProperty("serverName", serverName);
            body.addProperty("transportType", 2);

            JsonObject response = postWithBearer(DISCOVERY_BASE + "/host", body.toString());

            if (!response.has("serverToken") || !response.has("passcode")) {
                logger.error("[Discovery] /host response missing serverToken or passcode");
                return null;
            }

            this.serverToken = response.get("serverToken").getAsString();
            this.passcode = response.get("passcode").getAsString();

            return parseJoinCode(passcode);
        } catch (IOException e) {
            logger.error("[Discovery] Failed to host: " + e.getMessage());
            return null;
        }
    }

    /**
     * Send a heartbeat to keep the join code alive.
     * Should be called every 100 seconds.
     */
    public boolean heartbeat() {
        if (serverToken == null || passcode == null) return false;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("build", BUILD_NUMBER);
            body.addProperty("locale", "en_US");
            body.addProperty("passcode", passcode);
            body.addProperty("protocolVersion", 1);
            body.addProperty("transportType", 2);

            postWithServerToken(DISCOVERY_BASE + "/heartbeat", body.toString());
            return true;
        } catch (IOException e) {
            logger.error("[Discovery] Heartbeat failed: " + e.getMessage());
            return false;
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

            postWithServerToken(DISCOVERY_BASE + "/update", body.toString());
        } catch (IOException e) {
            logger.error("[Discovery] Update failed: " + e.getMessage());
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

            postWithServerToken(DISCOVERY_BASE + "/dehost", body.toString());
            logger.info("[Discovery] Dehosted successfully");
        } catch (IOException e) {
            logger.warning("[Discovery] Dehost failed: " + e.getMessage());
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
            int idx = Integer.parseInt(parts[i].trim());
            if (idx >= 0 && idx < CODE_SYMBOLS.length) {
                if (i > 0) sb.append(", ");
                sb.append(CODE_SYMBOLS[idx]);
            }
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

    // ---- HTTP Helpers ----

    private JsonObject postWithBearer(String url, String jsonBody) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + msAccessToken);
            con.setRequestProperty("api-version", "2.0");
            con.setRequestProperty("User-Agent", "libhttpclient/1.0.0.0");
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String err = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + err);
            }

            try (InputStreamReader isr = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(isr).getAsJsonObject();
            }
        } finally {
            con.disconnect();
        }
    }

    private void postWithServerToken(String url, String jsonBody) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + serverToken);
            con.setRequestProperty("api-version", "2.0");
            con.setRequestProperty("User-Agent", "libhttpclient/1.0.0.0");
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String err = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + err);
            }
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
