package org.geysermc.extension.edugeyser;

import org.geysermc.geyser.api.extension.Extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class TenantWhitelist {

    private static final String FILE_NAME = "tenant_whitelist.yml";
    private static final String LOG_PREFIX = "[TenantWhitelist] ";

    private final Path dataFolder;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Consumer<String> errorLogger;
    private final Set<String> allowedTenants = new HashSet<>();
    private boolean enabled = true;

    public TenantWhitelist(Extension extension) {
        this(extension.dataFolder(), extension.logger()::info,
                extension.logger()::warning, extension.logger()::error);
    }

    TenantWhitelist(Path dataFolder, Consumer<String> infoLogger,
                    Consumer<String> warningLogger, Consumer<String> errorLogger) {
        this.dataFolder = dataFolder;
        this.infoLogger = infoLogger;
        this.warningLogger = warningLogger;
        this.errorLogger = errorLogger;
    }

    public void load() {
        Path path = dataFolder.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            enabled = true;
            allowedTenants.clear();
            writeTemplate(path);
            return;
        }

        try {
            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(path).build();
            var root = loader.load();

            // If the on/off switch is missing (e.g. a config from before this option
            // existed), regenerate the whole file rather than work from a partial config.
            if (root.node("enabled").virtual()) {
                warningLogger.accept(LOG_PREFIX + "Config is missing the 'enabled' option; regenerating " + FILE_NAME + " from defaults.");
                writeTemplate(path);
                enabled = true;
                allowedTenants.clear();
                return;
            }

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
                infoLogger.accept(LOG_PREFIX + "Tenant whitelist is disabled (enabled: false). All tenants allowed; "
                        + allowedTenants.size() + " tenant(s) kept in config.");
            } else if (!allowedTenants.isEmpty()) {
                infoLogger.accept(LOG_PREFIX + "Tenant whitelist active with " + allowedTenants.size() + " tenant(s).");
            }
            // Enabled but empty is the default, unused state; stay silent to avoid log spam.
        } catch (Exception e) {
            // A malformed whitelist must not lock every Education client out of
            // the server. Disable enforcement explicitly so a future reload
            // cannot accidentally retain a previously loaded partial state.
            enabled = false;
            allowedTenants.clear();

            errorLogger.accept(LOG_PREFIX + "============================================");
            errorLogger.accept(LOG_PREFIX + "FAILED TO LOAD " + FILE_NAME);
            errorLogger.accept(LOG_PREFIX + "The tenant whitelist will NOT be applied; all tenants are allowed.");
            errorLogger.accept(LOG_PREFIX + "Fix the configuration and restart the server to enable it again.");
            errorLogger.accept(LOG_PREFIX + "Reason: " + e.getMessage());
            errorLogger.accept(LOG_PREFIX + "============================================");
        }
    }

    private void writeTemplate(Path path) {
        try {
            Files.createDirectories(dataFolder);
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
            errorLogger.accept(LOG_PREFIX + "Failed to write whitelist file: " + e.getMessage());
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
