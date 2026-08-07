package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntraOAuthClientTest {

    @Test
    void parsesTokenResponseAndRetainsUnrotatedRefreshToken() throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("access_token", "access");
        response.addProperty("expires_in", 600);
        long before = System.currentTimeMillis() / 1000L;

        EntraOAuthClient.Tokens tokens = EntraOAuthClient.parseTokens(response, "existing-refresh");

        assertEquals("access", tokens.accessToken());
        assertEquals("existing-refresh", tokens.refreshToken());
        assertTrue(tokens.accessTokenExpires() >= before + 600);
        assertTrue(tokens.accessTokenExpires() <= System.currentTimeMillis() / 1000L + 600);
    }

    @Test
    void prefersAbsoluteExpiryAndRotatedRefreshToken() throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("access_token", "access");
        response.addProperty("refresh_token", "rotated-refresh");
        response.addProperty("expires_on", 123456789L);
        response.addProperty("expires_in", 1);

        EntraOAuthClient.Tokens tokens = EntraOAuthClient.parseTokens(response, "old-refresh");

        assertEquals("rotated-refresh", tokens.refreshToken());
        assertEquals(123456789L, tokens.accessTokenExpires());
    }

    @Test
    void classifiesInteractiveLoginFromTypedNestedOAuthError() {
        IOException rejected = new EntraOAuthClient.OAuthException(
                400, "invalid_grant", "The refresh token was revoked");

        assertTrue(EntraOAuthClient.requiresInteractiveLogin(new CompletionException(rejected)));
        assertFalse(EntraOAuthClient.requiresInteractiveLogin(
                new EntraOAuthClient.OAuthException(503, "temporarily_unavailable", "Retry later")));
        assertFalse(EntraOAuthClient.requiresInteractiveLogin(
                new IOException("response text happens to contain invalid_grant")));
    }

    @Test
    void pollContinuesPastAuthorizationPending() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger requests = new AtomicInteger();
        try {
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false, (url, body) -> {
                if (requests.getAndIncrement() == 0) {
                    throw new EntraOAuthClient.OAuthException(400, "authorization_pending", "Keep polling");
                }
                JsonObject response = new JsonObject();
                response.addProperty("access_token", "access");
                response.addProperty("refresh_token", "refresh");
                response.addProperty("expires_in", 3600);
                return response;
            });
            EntraOAuthClient.DeviceAuthorization authorization = new EntraOAuthClient.DeviceAuthorization(
                    EntraOAuthClient.EDUCATION_CLIENT_ID, "device-code", "user-code",
                    "https://example.invalid", System.currentTimeMillis() + 10_000, 0);

            EntraOAuthClient.Tokens tokens = client.poll(authorization).get(2, TimeUnit.SECONDS);

            assertEquals("access", tokens.accessToken());
            assertEquals(2, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void slowDownAddsFiveSecondsToSubsequentPolls() {
        ImmediateScheduler scheduler = new ImmediateScheduler();
        AtomicInteger requests = new AtomicInteger();
        try {
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false, (url, body) -> {
                if (requests.getAndIncrement() == 0) {
                    throw new EntraOAuthClient.OAuthException(400, "slow_down", "Poll less frequently");
                }
                return tokenResponse();
            });

            EntraOAuthClient.Tokens tokens = client.poll(authorization(1)).join();

            assertEquals("access", tokens.accessToken());
            assertEquals(List.of(1L, 6L), scheduler.delaysSeconds);
            assertEquals(2, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void expiredTokenTerminatesPolling() {
        ImmediateScheduler scheduler = new ImmediateScheduler();
        try {
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false, (url, body) -> {
                throw new EntraOAuthClient.OAuthException(400, "expired_token", "The device code expired");
            });

            IOException failure = pollFailure(client, authorization(1));

            assertTrue(failure.getMessage().contains("expired before user completed sign-in"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shutdownTerminatesPollingWithoutCallingTransport() {
        ImmediateScheduler scheduler = new ImmediateScheduler();
        AtomicInteger requests = new AtomicInteger();
        try {
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> true, (url, body) -> {
                requests.incrementAndGet();
                return tokenResponse();
            });

            IOException failure = pollFailure(client, authorization(1));

            assertTrue(failure.getMessage().contains("interrupted by shutdown"));
            assertEquals(0, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void stoppedSchedulerFailsPollingInsteadOfLeavingFuturePending() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false,
                (url, body) -> tokenResponse());

        IOException failure = pollFailure(client, authorization(1));

        assertTrue(failure.getMessage().contains("scheduler stopped"));
    }

    @Test
    void rejectsMalformedDeviceCodeResponse() {
        ImmediateScheduler scheduler = new ImmediateScheduler();
        try {
            JsonObject malformed = new JsonObject();
            malformed.addProperty("device_code", "device-code");
            malformed.addProperty("user_code", "user-code");
            malformed.addProperty("verification_uri", "https://example.invalid");
            malformed.addProperty("expires_in", 900);
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false,
                    (url, body) -> malformed);

            IOException failure = assertThrows(IOException.class,
                    () -> client.requestDeviceCode(EntraOAuthClient.EDUCATION_CLIENT_ID));

            assertTrue(failure.getMessage().contains("Invalid device-code response"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsTokenResponseWithoutAccessToken() {
        JsonObject malformed = new JsonObject();
        malformed.addProperty("refresh_token", "refresh");
        malformed.addProperty("expires_in", 3600);

        IOException failure = assertThrows(IOException.class,
                () -> EntraOAuthClient.parseTokens(malformed, null));

        assertTrue(failure.getMessage().contains("does not contain access_token"));
    }

    @Test
    void refreshEncodesRequestAndRetainsUnrotatedRefreshToken() throws Exception {
        ImmediateScheduler scheduler = new ImmediateScheduler();
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        AtomicReference<String> requestedBody = new AtomicReference<>();
        try {
            EntraOAuthClient client = new EntraOAuthClient(scheduler, () -> false, (url, body) -> {
                requestedUrl.set(url);
                requestedBody.set(body);
                JsonObject response = new JsonObject();
                response.addProperty("access_token", "refreshed-access");
                response.addProperty("expires_in", 3600);
                return response;
            });

            EntraOAuthClient.Tokens tokens = client.refresh(
                    EntraOAuthClient.TOOLING_CLIENT_ID, "refresh token/+");

            assertTrue(requestedUrl.get().endsWith("/token"));
            assertTrue(requestedBody.get().contains("grant_type=refresh_token"));
            assertTrue(requestedBody.get().contains("client_id=" + EntraOAuthClient.TOOLING_CLIENT_ID));
            assertTrue(requestedBody.get().contains("refresh_token=refresh+token%2F%2B"));
            assertTrue(requestedBody.get().contains("offline_access"));
            assertEquals("refreshed-access", tokens.accessToken());
            assertEquals("refresh token/+", tokens.refreshToken());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static EntraOAuthClient.DeviceAuthorization authorization(int intervalSeconds) {
        return new EntraOAuthClient.DeviceAuthorization(
                EntraOAuthClient.EDUCATION_CLIENT_ID, "device-code", "user-code",
                "https://example.invalid", System.currentTimeMillis() + 10_000, intervalSeconds);
    }

    private static JsonObject tokenResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("access_token", "access");
        response.addProperty("refresh_token", "refresh");
        response.addProperty("expires_in", 3600);
        return response;
    }

    private static IOException pollFailure(EntraOAuthClient client,
                                           EntraOAuthClient.DeviceAuthorization authorization) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> client.poll(authorization).join());
        assertTrue(failure.getCause() instanceof IOException);
        return (IOException) failure.getCause();
    }

    private static final class ImmediateScheduler extends ScheduledThreadPoolExecutor {
        private final List<Long> delaysSeconds = new ArrayList<>();

        private ImmediateScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delaysSeconds.add(unit.toSeconds(delay));
            command.run();
            return CompletedScheduledFuture.INSTANCE;
        }
    }

    private static final class CompletedScheduledFuture implements ScheduledFuture<Object> {
        private static final CompletedScheduledFuture INSTANCE = new CompletedScheduledFuture();

        private CompletedScheduledFuture() {
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
