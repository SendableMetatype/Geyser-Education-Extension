package org.geysermc.extension.edugeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantWhitelistTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enabledWhitelistAllowsOnlyConfiguredTenants() throws IOException {
        writeConfig("""
                enabled: true
                tenants:
                  - tenant-a
                  - " tenant-b "
                  - ""
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);

        whitelist.load();

        assertTrue(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("tenant-a"));
        assertTrue(whitelist.isAllowed("tenant-b"));
        assertFalse(whitelist.isAllowed("tenant-c"));
        assertFalse(whitelist.isAllowed(null));
    }

    @Test
    void disabledWhitelistAllowsEveryoneWhileRetainingConfiguredTenants() throws IOException {
        writeConfig("""
                enabled: false
                tenants:
                  - tenant-a
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);

        whitelist.load();

        assertFalse(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("tenant-a"));
        assertTrue(whitelist.isAllowed("tenant-b"));
        assertTrue(whitelist.isAllowed(null));
    }

    @Test
    void enabledWhitelistWithNoTenantsDoesNotEnforce() throws IOException {
        writeConfig("""
                enabled: true
                tenants: []
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);

        whitelist.load();

        assertFalse(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("any-tenant"));
        assertTrue(whitelist.isAllowed(null));
    }

    @Test
    void malformedReloadClearsPreviouslyEnforcedStateAndLogsFailOpen() throws IOException {
        writeConfig("""
                enabled: true
                tenants:
                  - tenant-a
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);
        whitelist.load();
        assertTrue(whitelist.isEnabled());

        writeConfig("""
                enabled: true
                tenants: ["unterminated
                """);
        whitelist.load();

        assertFalse(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("tenant-a"));
        assertTrue(whitelist.isAllowed("any-tenant"));
        assertTrue(logger.errors.stream().anyMatch(message ->
                message.contains("whitelist will NOT be applied")));
    }

    @Test
    void missingEnabledOptionRegeneratesFailOpenDefaults() throws IOException {
        writeConfig("""
                tenants:
                  - tenant-a
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);

        whitelist.load();

        assertFalse(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("any-tenant"));
        String regenerated = Files.readString(configPath());
        assertTrue(regenerated.contains("enabled: true"));
        assertFalse(regenerated.contains("tenant-a"));
        assertTrue(logger.warnings.stream().anyMatch(message ->
                message.contains("missing the 'enabled' option")));
    }

    @Test
    void missingFileReloadClearsPreviouslyEnforcedState() throws IOException {
        writeConfig("""
                enabled: true
                tenants:
                  - tenant-a
                """);
        RecordingLogger logger = new RecordingLogger();
        TenantWhitelist whitelist = whitelist(logger);
        whitelist.load();
        assertTrue(whitelist.isEnabled());

        Files.delete(configPath());
        whitelist.load();

        assertFalse(whitelist.isEnabled());
        assertTrue(whitelist.isAllowed("any-tenant"));
        assertTrue(Files.exists(configPath()));
        assertTrue(Files.readString(configPath()).contains("enabled: true"));
    }

    private void writeConfig(String contents) throws IOException {
        Files.writeString(configPath(), contents);
    }

    private Path configPath() {
        return temporaryDirectory.resolve("tenant_whitelist.yml");
    }

    private TenantWhitelist whitelist(RecordingLogger logger) {
        return new TenantWhitelist(temporaryDirectory, logger::info, logger::warning, logger::error);
    }

    private static final class RecordingLogger {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private void error(String message) {
            errors.add(message);
        }

        private void warning(String message) {
            warnings.add(message);
        }

        private void info(String message) {
        }
    }
}
