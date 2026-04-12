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
import java.util.UUID;

/**
 * Handles PlayFab authentication and MCToken acquisition for Education Edition.
 * The MCToken is required for Nethernet signaling WebSocket authentication.
 *
 * Auth chain: PlayFab LoginWithCustomID -> SessionTicket -> session/start -> MCToken
 */
public class PlayFabTokenManager {

    private static final String PLAYFAB_BASE = "https://6955f.playfabapi.com";
    private static final String SESSION_START_URL = "https://authorization.franchise.minecraft-services.net/api/v1.0/session/start";
    private static final String TITLE_ID = "6955F";
    private static final int HTTP_TIMEOUT = 15000;

    private final ExtensionLogger logger;
    private final String customId;
    private final String deviceId;

    private volatile @Nullable String sessionTicket;
    private volatile @Nullable String mcToken;
    private volatile @Nullable String mcTokenExpiry;

    public PlayFabTokenManager(ExtensionLogger logger) {
        this.logger = logger;
        // Generate persistent IDs - use consistent format as the real client
        this.customId = "MCPF" + UUID.randomUUID().toString().replace("-", "").substring(0, 32).toUpperCase();
        this.deviceId = UUID.randomUUID().toString().replace("-", "").toLowerCase();
    }

    /**
     * Performs the full auth chain: PlayFab login -> MCToken acquisition.
     * @return the MCToken authorization header (e.g. "MCToken eyJ..."), or null on failure
     */
    public @Nullable String authenticate() {
        try {
            loginToPlayFab();
            obtainMCToken();
            return mcToken;
        } catch (Exception e) {
            logger.error("[PlayFab] Authentication failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * @return the current MCToken authorization header, or null if not authenticated
     */
    public @Nullable String getMCToken() {
        return mcToken;
    }

    // ---- PlayFab Login ----

    private void loginToPlayFab() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("CreateAccount", true);
        payload.addProperty("CustomId", customId);
        payload.add("EncryptedRequest", null);

        JsonObject infoParams = new JsonObject();
        infoParams.addProperty("GetCharacterInventories", false);
        infoParams.addProperty("GetCharacterList", false);
        infoParams.addProperty("GetPlayerProfile", true);
        infoParams.addProperty("GetPlayerStatistics", false);
        infoParams.addProperty("GetTitleData", false);
        infoParams.addProperty("GetUserAccountInfo", true);
        infoParams.addProperty("GetUserData", false);
        infoParams.addProperty("GetUserInventory", false);
        infoParams.addProperty("GetUserReadOnlyData", false);
        infoParams.addProperty("GetUserVirtualCurrency", false);
        payload.add("InfoRequestParameters", infoParams);
        payload.add("PlayerSecret", null);
        payload.addProperty("TitleId", TITLE_ID);

        JsonObject response = postJson(PLAYFAB_BASE + "/Client/LoginWithCustomID", payload.toString(), null);

        if (!response.has("data") || !response.getAsJsonObject("data").has("SessionTicket")) {
            throw new IOException("PlayFab login response missing SessionTicket");
        }

        this.sessionTicket = response.getAsJsonObject("data").get("SessionTicket").getAsString();
        logger.debug("[PlayFab] Logged in successfully");
    }

    // ---- MCToken Acquisition ----

    private void obtainMCToken() throws IOException {
        if (sessionTicket == null) {
            throw new IOException("No PlayFab session ticket available");
        }

        JsonObject device = new JsonObject();
        device.addProperty("applicationType", "MinecraftPE");
        device.add("capabilities", null);
        device.addProperty("gameVersion", "1.21.10");
        device.addProperty("id", deviceId);
        device.addProperty("memory", "2147483647");
        device.addProperty("platform", "Win32");
        device.addProperty("playFabTitleId", TITLE_ID);
        device.addProperty("storePlatform", "uwp.store");
        device.add("treatmentOverrides", null);
        device.addProperty("type", "Win32");

        JsonObject user = new JsonObject();
        user.addProperty("language", "en");
        user.addProperty("languageCode", "en-US");
        user.addProperty("regionCode", "US");
        user.addProperty("token", sessionTicket);
        user.addProperty("tokenType", "PlayFab");

        JsonObject payload = new JsonObject();
        payload.add("device", device);
        payload.add("user", user);

        JsonObject response = postJson(SESSION_START_URL, payload.toString(), "libhttpclient/1.0.0.0");

        if (!response.has("result") || !response.getAsJsonObject("result").has("authorizationHeader")) {
            throw new IOException("session/start response missing authorizationHeader");
        }

        JsonObject result = response.getAsJsonObject("result");
        this.mcToken = result.get("authorizationHeader").getAsString();
        if (result.has("validUntil")) {
            this.mcTokenExpiry = result.get("validUntil").getAsString();
        }

        logger.debug("[PlayFab] MCToken obtained successfully");
    }

    // ---- HTTP Helper ----

    private JsonObject postJson(String url, String jsonBody, @Nullable String userAgent) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            if (userAgent != null) {
                con.setRequestProperty("User-Agent", userAgent);
            }
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
