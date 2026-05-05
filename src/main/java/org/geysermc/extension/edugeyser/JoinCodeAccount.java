package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Holds the session state for a single join code registration.
 * Each account corresponds to one education tenant. Join codes are tenant-scoped.
 * All accounts' Discovery registrations point to the same connection ID
 * owned by Geyser's Nethernet server.
 */
public class JoinCodeAccount {
    // Auth state (from device code OAuth flow)
    volatile @Nullable String refreshToken;
    volatile @Nullable String accessToken;
    volatile long accessTokenExpires;

    // Discovery state
    volatile @Nullable String passcode;
    volatile @Nullable String serverToken;

    // Runtime (not persisted)
    volatile @Nullable String tenantId;
    volatile @Nullable String upn;
    volatile @Nullable String humanReadableCode;
    volatile @Nullable DiscoveryClient discoveryClient;
    volatile boolean active = false;

    /**
     * Extract the tenant ID from the Discovery server token (first pipe segment).
     */
    void extractTenantId() {
        if (serverToken != null && serverToken.contains("|")) {
            tenantId = serverToken.split("\\|")[0];
        }
    }

    /**
     * Extract UPN and tenant ID from the access token JWT payload.
     */
    void extractTokenClaims() {
        if (accessToken == null) return;
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return;
            String padded = parts[1];
            while (padded.length() % 4 != 0) padded += "=";
            String json = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            JsonObject claims = JsonParser.parseString(json).getAsJsonObject();
            if (claims.has("upn")) {
                upn = claims.get("upn").getAsString();
            } else if (claims.has("preferred_username")) {
                upn = claims.get("preferred_username").getAsString();
            }
            if (claims.has("tid")) {
                tenantId = claims.get("tid").getAsString();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Display label: UPN if available, otherwise tenant ID, otherwise "unknown".
     */
    String displayLabel() {
        if (upn != null) return upn;
        if (tenantId != null) return tenantId;
        return "unknown";
    }
}
