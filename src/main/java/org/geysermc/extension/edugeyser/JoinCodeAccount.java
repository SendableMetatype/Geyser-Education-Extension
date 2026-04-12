package org.geysermc.extension.edugeyser;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.extension.edugeyser.joincode.JoinCodeNetherNetServer;

/**
 * Holds the session state for a single join code registration.
 * Each account corresponds to one education tenant — join codes are tenant-scoped.
 * Each account gets its own Nethernet server (fresh ID each startup) that redirects
 * to the shared Geyser RakNet port.
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
    volatile @Nullable String humanReadableCode;
    volatile @Nullable DiscoveryClient discoveryClient;
    volatile @Nullable JoinCodeNetherNetServer netherNetServer;
    volatile boolean active = false;

    /**
     * Extract the tenant ID from the Discovery server token (first pipe segment).
     */
    void extractTenantId() {
        if (serverToken != null && serverToken.contains("|")) {
            tenantId = serverToken.split("\\|")[0];
        }
    }
}
