package org.geysermc.extension.edugeyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessServerListManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void malformedAccountDoesNotPreventLaterAccountsFromLoading() throws IOException {
        Path sessions = temporaryDirectory.resolve("sessions_serverlist.yml");
        Files.writeString(sessions, """
                accounts:
                  - server-id: first
                    refresh-token: first-refresh
                  - this-entry-is-not-a-mapping
                  - server-id: third
                    refresh-token: third-refresh
                """);
        List<Integer> malformedEntries = new ArrayList<>();

        List<ServerListAccount> accounts = MessServerListManager.readAccounts(sessions,
                (entry, error) -> malformedEntries.add(entry));

        assertEquals(List.of(2), malformedEntries);
        assertEquals(2, accounts.size());
        assertEquals("first", accounts.get(0).serverId);
        assertEquals("first-refresh", accounts.get(0).refreshToken);
        assertEquals("third", accounts.get(1).serverId);
        assertEquals("third-refresh", accounts.get(1).refreshToken);
    }

    @Test
    void acceptsTokensFromTheSameTenant() {
        String tenantId = "03b5e7a1-cb09-4417-9e1a-c686b440b2c5";

        assertDoesNotThrow(() -> MessServerListManager.validateMatchingTenants(
                tokenWithTenant(tenantId), tokenWithTenant(tenantId.toUpperCase())));
    }

    @Test
    void rejectsTokensFromDifferentTenants() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MessServerListManager.validateMatchingTenants(
                        tokenWithTenant("03b5e7a1-cb09-4417-9e1a-c686b440b2c5"),
                        tokenWithTenant("947c132d-8d16-4f4e-a05b-183892904149")));

        assertTrue(exception.getMessage().contains("different tenants"));
    }

    @Test
    void rejectsTokenWithoutTenantClaim() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MessServerListManager.validateMatchingTenants(
                        tokenWithPayload("{}"),
                        tokenWithTenant("03b5e7a1-cb09-4417-9e1a-c686b440b2c5")));

        assertTrue(exception.getMessage().contains("tooling sign-in token"));
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(IllegalStateException.class,
                () -> MessServerListManager.validateMatchingTenants(
                        "not-a-jwt",
                        tokenWithTenant("03b5e7a1-cb09-4417-9e1a-c686b440b2c5")));
    }

    @Test
    void acceptsBlankAndValidServerPorts() {
        assertEquals(-1, MessServerListManager.parseServerPort(null));
        assertEquals(-1, MessServerListManager.parseServerPort("  "));
        assertEquals(1, MessServerListManager.parseServerPort("1"));
        assertEquals(65535, MessServerListManager.parseServerPort("65535"));
    }

    @Test
    void rejectsInvalidServerPorts() {
        assertThrows(IllegalArgumentException.class, () -> MessServerListManager.parseServerPort("0"));
        assertThrows(IllegalArgumentException.class, () -> MessServerListManager.parseServerPort("-1"));
        assertThrows(IllegalArgumentException.class, () -> MessServerListManager.parseServerPort("65536"));
        assertThrows(IllegalArgumentException.class, () -> MessServerListManager.parseServerPort("not-a-port"));
    }

    @Test
    void formatsRawAndBracketedIpv6WithOneBracketPair() {
        assertEquals("[2001:db8::1]:19132",
                MessServerListManager.formatIpPort("2001:db8::1", 19132));
        assertEquals("[2001:db8::1]:19132",
                MessServerListManager.formatIpPort("[2001:db8::1]", 19132));
    }

    @Test
    void leavesIpv4AndHostnamesUnbracketed() {
        assertEquals("192.0.2.1:19132",
                MessServerListManager.formatIpPort("192.0.2.1", 19132));
        assertEquals("mc.example.com:19132",
                MessServerListManager.formatIpPort("mc.example.com", 19132));
    }

    private static String tokenWithTenant(String tenantId) {
        return tokenWithPayload("{\"tid\":\"" + tenantId + "\"}");
    }

    private static String tokenWithPayload(String payload) {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "header." + encodedPayload + ".signature";
    }
}
