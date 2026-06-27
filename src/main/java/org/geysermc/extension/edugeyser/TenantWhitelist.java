package org.geysermc.extension.edugeyser;

import org.geysermc.geyser.api.extension.Extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class TenantWhitelist {

    private static final String FILE_NAME = "tenant_whitelist.yml";
    private static final String LOG_PREFIX = "[edu] [TenantWhitelist] ";

    private final Extension extension;
    private final Set<String> allowedTenants = new HashSet<>();
    private boolean enabled = true;

    public TenantWhitelist(Extension extension) {
        this.extension = extension;
    }

    public void load() {
        Path path = extension.dataFolder().resolve(FILE_NAME);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(extension.dataFolder());
                Files.writeString(path,
                        "# tenant_whitelist.yml\n" +
                        "#\n" +
                        "# ADVANCED FEATURE. Restricts which organizations (Microsoft Entra\n" +
                        "# tenants) are allowed to join. Configuring this incorrectly can stop\n" +
                        "# legitimate players from joining, so leave the tenant list empty\n" +
                        "# unless you specifically need it.\n" +
                        "#\n" +
                        "# enabled: master on/off switch. Set to false to turn the whitelist\n" +
                        "#   off without removing your tenant list below, so you can re-enable\n" +
                        "#   it later.\n" +
                        "# tenants: the allowed tenant IDs. While enabled is true, an empty\n" +
                        "#   list allows everyone and a non-empty list allows only those tenants.\n" +
                        "#\n" +
                        "# How to find your tenant ID:\n" +
                        "#   https://learn.microsoft.com/en-us/sharepoint/find-your-office-365-tenant-id\n" +
                        "#   https://tenantidlookup.com/\n" +
                        "#\n" +
                        "# Example: \"03b5e7a1-cb09-4417-9e1a-c686b440b2c5\"\n" +
                        "enabled: true\n" +
                        "tenants:\n" +
                        "  - \"\"\n" +
                        "  - \"\"\n" +
                        "  - \"\"\n");
            } catch (IOException e) {
                extension.logger().error(LOG_PREFIX + "Failed to create whitelist file: " + e.getMessage());
            }
            return;
        }

        try {
            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(path).build();
            var root = loader.load();
            enabled = root.node("enabled").getBoolean(true);
            var tenantsList = root.node("tenants").getList(String.class);
            allowedTenants.clear();
            if (tenantsList != null) {
                for (String tenant : tenantsList) {
                    if (tenant != null && !tenant.isBlank()) {
                        allowedTenants.add(tenant.trim());
                    }
                }
            }
            if (!enabled) {
                extension.logger().info(LOG_PREFIX + "Tenant whitelist is disabled (enabled: false). All tenants allowed; "
                        + allowedTenants.size() + " tenant(s) kept in config.");
            } else if (!allowedTenants.isEmpty()) {
                extension.logger().info(LOG_PREFIX + "Tenant whitelist active with " + allowedTenants.size() + " tenant(s).");
            } else {
                extension.logger().info(LOG_PREFIX + "Tenant whitelist is empty. All tenants are allowed.");
            }
        } catch (Exception e) {
            extension.logger().error(LOG_PREFIX + "Failed to load whitelist: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        // Actively enforcing requires the master switch on AND at least one tenant listed.
        return enabled && !allowedTenants.isEmpty();
    }

    public boolean isAllowed(String tenantId) {
        if (!isEnabled()) return true;
        return tenantId != null && allowedTenants.contains(tenantId);
    }
}
