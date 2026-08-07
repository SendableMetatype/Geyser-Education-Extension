package org.geysermc.extension.edugeyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Shared Microsoft Entra device-code and refresh-token implementation. */
final class EntraOAuthClient {

    static final String TOOLING_CLIENT_ID = "1c91b067-6806-44a5-8d2d-3137e625f5b8";
    static final String EDUCATION_CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2";

    private static final String SCOPE = "16556bfc-5102-43c9-a82a-3ea5e4810689/.default offline_access";
    private static final String ENTRA_BASE = "https://login.microsoftonline.com/organizations/oauth2/v2.0";
    private static final int HTTP_TIMEOUT = 15000;

    private final ScheduledExecutorService scheduler;
    private final BooleanSupplier shutdownRequested;
    private final FormTransport transport;

    EntraOAuthClient(ScheduledExecutorService scheduler, BooleanSupplier shutdownRequested) {
        this(scheduler, shutdownRequested, EntraOAuthClient::postForm);
    }

    EntraOAuthClient(ScheduledExecutorService scheduler, BooleanSupplier shutdownRequested, FormTransport transport) {
        this.scheduler = scheduler;
        this.shutdownRequested = shutdownRequested;
        this.transport = transport;
    }

    DeviceAuthorization requestDeviceCode(String clientId) throws IOException {
        String body = "client_id=" + encode(clientId) + "&scope=" + encode(SCOPE);
        JsonObject response = transport.post(ENTRA_BASE + "/devicecode", body);
        throwIfOAuthError(response, 200);
        try {
            String verificationUri = response.has("verification_uri")
                    ? response.get("verification_uri").getAsString()
                    : response.get("verification_url").getAsString();
            int interval = response.get("interval").getAsInt();
            if (interval < 1) {
                throw new IOException("Device-code response contains an invalid polling interval: " + interval);
            }
            return new DeviceAuthorization(
                    clientId,
                    response.get("device_code").getAsString(),
                    response.get("user_code").getAsString(),
                    verificationUri,
                    System.currentTimeMillis() + response.get("expires_in").getAsLong() * 1000L,
                    interval);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Invalid device-code response from Entra", e);
        }
    }

    CompletableFuture<Tokens> poll(DeviceAuthorization authorization) {
        CompletableFuture<Tokens> future = new CompletableFuture<>();
        String pollBody = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + encode(authorization.clientId())
                + "&device_code=" + encode(authorization.deviceCode());
        schedulePollTick(future, pollBody, authorization.expiresAtMillis(),
                new AtomicInteger(authorization.pollIntervalSeconds()));
        return future;
    }

    Tokens refresh(String clientId, String refreshToken) throws IOException {
        String body = "grant_type=refresh_token"
                + "&client_id=" + encode(clientId)
                + "&refresh_token=" + encode(refreshToken)
                + "&scope=" + encode(SCOPE);
        return parseTokens(transport.post(ENTRA_BASE + "/token", body), refreshToken);
    }

    private void schedulePollTick(CompletableFuture<Tokens> future, String pollBody,
                                  long deadline, AtomicInteger interval) {
        try {
            scheduler.schedule(() -> {
                if (future.isDone()) return;
                if (shutdownRequested.getAsBoolean()) {
                    future.completeExceptionally(new IOException("Device-code flow interrupted by shutdown"));
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    future.completeExceptionally(new IOException("Device code expired"));
                    return;
                }

                try {
                    future.complete(parseTokens(transport.post(ENTRA_BASE + "/token", pollBody), null));
                } catch (OAuthException e) {
                    if (e.hasCode("authorization_pending")) {
                        schedulePollTick(future, pollBody, deadline, interval);
                    } else if (e.hasCode("slow_down")) {
                        interval.addAndGet(5);
                        schedulePollTick(future, pollBody, deadline, interval);
                    } else if (e.hasCode("expired_token")) {
                        future.completeExceptionally(new IOException(
                                "Device code expired before user completed sign-in", e));
                    } else {
                        future.completeExceptionally(e);
                    }
                } catch (IOException | RuntimeException e) {
                    future.completeExceptionally(e);
                }
            }, interval.get(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IOException("Device-code flow interrupted because its scheduler stopped", e));
        }
    }

    static Tokens parseTokens(JsonObject response, @Nullable String fallbackRefreshToken) throws IOException {
        throwIfOAuthError(response, 200);
        if (!response.has("access_token")) {
            throw new IOException("Token response does not contain access_token");
        }

        String refreshToken = response.has("refresh_token")
                ? response.get("refresh_token").getAsString() : fallbackRefreshToken;
        long now = System.currentTimeMillis() / 1000L;
        long expiresAt;
        if (response.has("expires_on")) {
            expiresAt = response.get("expires_on").getAsLong();
        } else if (response.has("expires_in")) {
            expiresAt = now + response.get("expires_in").getAsLong();
        } else {
            // Entra normally supplies expires_in. Keep a bounded useful token if
            // a compatible endpoint omits it instead of refreshing continuously.
            expiresAt = now + 3600;
        }
        return new Tokens(response.get("access_token").getAsString(), refreshToken, expiresAt);
    }

    static boolean requiresInteractiveLogin(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof OAuthException oauthException
                    && (oauthException.hasCode("invalid_grant")
                    || oauthException.hasCode("interaction_required")
                    || oauthException.hasCode("consent_required"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonObject postForm(String url, String formBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setConnectTimeout(HTTP_TIMEOUT);
            connection.setReadTimeout(HTTP_TIMEOUT);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(formBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            if (status >= 400) {
                throw oauthError(status, readStream(connection.getErrorStream()));
            }
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static OAuthException oauthError(int status, String responseBody) {
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            String code = response.has("error") ? response.get("error").getAsString() : null;
            String description = response.has("error_description")
                    ? response.get("error_description").getAsString() : responseBody;
            return new OAuthException(status, code, description);
        } catch (RuntimeException ignored) {
            return new OAuthException(status, null, responseBody);
        }
    }

    private static void throwIfOAuthError(JsonObject response, int status) throws OAuthException {
        if (!response.has("error")) return;
        String code = response.get("error").getAsString();
        String description = response.has("error_description")
                ? response.get("error_description").getAsString() : response.toString();
        throw new OAuthException(status, code, description);
    }

    private static String readStream(@Nullable InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }

    record DeviceAuthorization(String clientId, String deviceCode, String userCode,
                               String verificationUri, long expiresAtMillis, int pollIntervalSeconds) {
    }

    record Tokens(String accessToken, @Nullable String refreshToken, long accessTokenExpires) {
    }

    static final class OAuthException extends IOException {
        private final int status;
        private final @Nullable String errorCode;

        OAuthException(int status, @Nullable String errorCode, String description) {
            super((errorCode == null ? "OAuth request failed" : errorCode)
                    + " (HTTP " + status + "): " + description);
            this.status = status;
            this.errorCode = errorCode;
        }

        int status() {
            return status;
        }

        @Nullable String errorCode() {
            return errorCode;
        }

        boolean hasCode(String code) {
            return code.equals(errorCode);
        }
    }

    @FunctionalInterface
    interface FormTransport {
        JsonObject post(String url, String formBody) throws IOException;
    }
}
