package org.geysermc.extension.edugeyser;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Holds the session state for a single MESS server list registration.
 * Each account corresponds to one Global Admin M365 tenant.
 * Global config (server-name, server-ip, max-players) is NOT stored here —
 * it lives in serverlist_config.yml and is read by MessServerListManager.
 */
public class ServerListAccount {
    // Auth state
    volatile @Nullable String serverId;
    volatile @Nullable String refreshToken;
    volatile @Nullable String accessToken;
    volatile long accessTokenExpires;
    volatile @Nullable String eduRefreshToken;
    volatile @Nullable String eduAccessToken;
    volatile long eduAccessTokenExpires;
    volatile @Nullable String serverToken;
    volatile @Nullable String serverTokenJwt;
    volatile long serverTokenExpires;

    // Runtime
    volatile @Nullable String tenantId;
    volatile boolean active = false;

    /**
     * Extract the tenant ID from the server token (first pipe segment).
     */
    void extractTenantId() {
        if (serverToken != null && serverToken.contains("|")) {
            tenantId = serverToken.split("\\|")[0];
        }
    }
}
