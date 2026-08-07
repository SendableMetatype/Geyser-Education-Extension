package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryClientTest {

    @Test
    void hostUsesCapturedEducationClientContract() {
        AtomicReference<DiscoveryClient.DiscoveryRequest> captured = new AtomicReference<>();
        DiscoveryClient client = client("microsoft-access-token", request -> {
            captured.set(request);
            JsonObject response = new JsonObject();
            response.addProperty("serverToken", "saved-server-token");
            response.addProperty("passcode", "15,3,15,1");
            return response;
        });

        String joinCode = client.host("nethernet-id", "World Name", "Server Name");

        assertEquals("Potion, Alex, Potion, Balloon", joinCode);
        assertEquals("saved-server-token", client.getServerToken());
        assertEquals("15,3,15,1", client.getPasscode());

        DiscoveryClient.DiscoveryRequest request = captured.get();
        assertNotNull(request);
        assertEquals("https://discovery.minecrafteduservices.com/host", request.url());
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals("Bearer microsoft-access-token", request.headers().get("Authorization"));
        assertEquals("2.0", request.headers().get("api-version"));
        assertEquals("libhttpclient/1.0.0.0", request.headers().get("User-Agent"));
        assertTrue(request.expectsJsonResponse());

        JsonObject body = body(request);
        assertEquals(9, body.size());
        assertEquals(12632000, body.get("build").getAsInt());
        assertEquals("en_US", body.get("locale").getAsString());
        assertEquals(40, body.get("maxPlayers").getAsInt());
        assertEquals("nethernet-id", body.get("networkId").getAsString());
        assertEquals(1, body.get("playerCount").getAsInt());
        assertEquals(1, body.get("protocolVersion").getAsInt());
        assertEquals("Server Name", body.get("serverDetails").getAsString());
        assertEquals("World Name", body.get("serverName").getAsString());
        assertEquals(2, body.get("transportType").getAsInt());
    }

    @Test
    void heartbeatMatchesCapturedEducationClientPayload() {
        AtomicReference<DiscoveryClient.DiscoveryRequest> captured = new AtomicReference<>();
        DiscoveryClient client = client("unused-access-token", request -> {
            captured.set(request);
            return null;
        });
        client.setServerToken("saved-server-token");
        client.setPasscode("15,3,15,1");

        assertEquals(DiscoveryClient.HeartbeatResult.OK, client.heartbeat());

        DiscoveryClient.DiscoveryRequest request = captured.get();
        assertNotNull(request);
        assertEquals("https://discovery.minecrafteduservices.com/heartbeat", request.url());
        assertEquals("Bearer saved-server-token", request.headers().get("Authorization"));
        assertFalse(request.expectsJsonResponse());

        JsonObject body = body(request);
        assertEquals(5, body.size());
        assertEquals(12632000, body.get("build").getAsInt());
        assertEquals("en_US", body.get("locale").getAsString());
        assertEquals("15,3,15,1", body.get("passcode").getAsString());
        assertEquals(1, body.get("protocolVersion").getAsInt());
        assertEquals(2, body.get("transportType").getAsInt());
        assertFalse(body.has("playerCount"));
        assertFalse(body.has("maxPlayers"));
        assertFalse(body.has("serverName"));
        assertFalse(body.has("serverDetails"));
    }

    @Test
    void heartbeatClassifiesRejectedOrMissingRegistrationsAsDead() {
        for (int status : new int[] {401, 403, 404}) {
            DiscoveryClient client = client("unused-access-token", request -> {
                throw new DiscoveryClient.HttpStatusException(status, "HTTP " + status);
            });
            client.setServerToken("saved-server-token");
            client.setPasscode("15,3,15,1");

            assertEquals(DiscoveryClient.HeartbeatResult.REGISTRATION_DEAD, client.heartbeat(),
                    "HTTP " + status + " should invalidate the registration");
        }
    }

    @Test
    void heartbeatTreatsServerAndNetworkFailuresAsTransient() {
        DiscoveryClient serverFailure = client("unused-access-token", request -> {
            throw new DiscoveryClient.HttpStatusException(500, "HTTP 500");
        });
        serverFailure.setServerToken("saved-server-token");
        serverFailure.setPasscode("15,3,15,1");

        DiscoveryClient timeout = client("unused-access-token", request -> {
            throw new IOException("timed out");
        });
        timeout.setServerToken("saved-server-token");
        timeout.setPasscode("15,3,15,1");

        assertEquals(DiscoveryClient.HeartbeatResult.TRANSIENT, serverFailure.heartbeat());
        assertEquals(DiscoveryClient.HeartbeatResult.TRANSIENT, timeout.heartbeat());
    }

    @Test
    void dehostUsesSavedRegistrationCredentials() {
        AtomicReference<DiscoveryClient.DiscoveryRequest> captured = new AtomicReference<>();
        DiscoveryClient client = client("unused-access-token", request -> {
            captured.set(request);
            return null;
        });
        client.setServerToken("saved-server-token");
        client.setPasscode("15,3,15,1");

        client.dehost();

        DiscoveryClient.DiscoveryRequest request = captured.get();
        assertNotNull(request);
        assertEquals("https://discovery.minecrafteduservices.com/dehost", request.url());
        assertEquals("Bearer saved-server-token", request.headers().get("Authorization"));
        assertFalse(request.expectsJsonResponse());

        JsonObject body = body(request);
        assertEquals(4, body.size());
        assertEquals(12632000, body.get("build").getAsInt());
        assertEquals("en_US", body.get("locale").getAsString());
        assertEquals("15,3,15,1", body.get("passcode").getAsString());
        assertEquals(1, body.get("protocolVersion").getAsInt());
    }

    private static DiscoveryClient client(String accessToken, DiscoveryClient.Transport transport) {
        return new DiscoveryClient(message -> { }, message -> { }, message -> { }, accessToken, transport);
    }

    private static JsonObject body(DiscoveryClient.DiscoveryRequest request) {
        return JsonParser.parseString(request.jsonBody()).getAsJsonObject();
    }
}
