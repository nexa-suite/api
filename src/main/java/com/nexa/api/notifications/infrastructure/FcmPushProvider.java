package com.nexa.api.notifications.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** FCM HTTP v1 transport. The caller supplies a tenant-bound destination resolver. */
public final class FcmPushProvider implements PushProviderPort {
    static final String GENERIC_NOTIFICATION_TITLE = "Nexa";
    static final String GENERIC_NOTIFICATION_BODY = "Hay una actualización disponible. Abre Nexa para revisarla.";
    static final String ANDROID_NOTIFICATION_CHANNEL_ID = "nexa_notifications";
    @FunctionalInterface public interface DestinationResolver { String resolve(UUID tenantId, UUID workspaceId, UUID subscriptionId); }
    /** Invalidate only if the stored token still equals the attempted token; rotation must win. */
    @FunctionalInterface public interface InvalidDestinationHandler { void invalidate(UUID tenantId, UUID workspaceId, UUID subscriptionId, String destination); }
    @FunctionalInterface public interface AccessTokenSource { String accessToken() throws IOException; }
    @FunctionalInterface interface Sender { Response send(URI endpoint, String authorization, String body) throws IOException, InterruptedException; }
    record Response(int status, String body, String retryAfter) { Response(int status, String body) { this(status, body, null); } }

    private final URI endpoint;
    private final DestinationResolver destinations;
    private final AccessTokenSource credentials;
    private final InvalidDestinationHandler invalidator;
    private final Sender sender;
    private final ObjectMapper json;

    public FcmPushProvider(String projectId, DestinationResolver destinations, InvalidDestinationHandler invalidator, AccessTokenSource credentials, ObjectMapper json) {
        this(projectId, destinations, invalidator, credentials, json, httpSender());
    }

    FcmPushProvider(String projectId, DestinationResolver destinations, InvalidDestinationHandler invalidator, AccessTokenSource credentials, ObjectMapper json, Sender sender) {
        requireProjectId(projectId);
        this.endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");
        this.destinations = Objects.requireNonNull(destinations);
        this.credentials = Objects.requireNonNull(credentials);
        this.invalidator = Objects.requireNonNull(invalidator);
        this.sender = Objects.requireNonNull(sender);
        this.json = Objects.requireNonNull(json);
    }

    @Override public DeliveryResult deliver(Delivery delivery) {
        try {
            if (delivery.tenantId() == null || delivery.workspaceId() == null) return result("DEFERRED", "SCOPE_UNAVAILABLE");
            String destination = destinations.resolve(delivery.tenantId(), delivery.workspaceId(), delivery.subscriptionId());
            if (destination == null || destination.isBlank()) return result("DEFERRED", "DESTINATION_UNAVAILABLE");
            String targetField = "token";
            String target = destination;
            if (destination.startsWith("fcm-fid:")) {
                targetField = "fid";
                target = destination.substring("fcm-fid:".length());
                if (!target.matches("[A-Za-z0-9_-]{11,160}")) return result("FAILED", "FCM_DESTINATION_INVALID");
            } else if (destination.startsWith("fcm-")) {
                return result("FAILED", "FCM_DESTINATION_UNSUPPORTED");
            }
            String bearer = credentials.accessToken();
            if (bearer == null || bearer.isBlank()) return result("DEFERRED", "CREDENTIALS_UNAVAILABLE");
            // Payload is a wake-up hint only. Fetching the notification still requires current authorization.
            String body = json.writeValueAsString(Map.of("message", Map.of(targetField, target,
                    "notification", Map.of("title", GENERIC_NOTIFICATION_TITLE, "body", GENERIC_NOTIFICATION_BODY),
                    "android", Map.of("notification", Map.of("channel_id", ANDROID_NOTIFICATION_CHANNEL_ID)),
                    "data", Map.of("eventId", delivery.eventId()))));
            Response response = sender.send(endpoint, "Bearer " + bearer, body);
            if (response.status() >= 200 && response.status() < 300) return result("SENT", "FCM_ACCEPTED");
            if (isUnregistered(response)) {
                invalidator.invalidate(delivery.tenantId(), delivery.workspaceId(), delivery.subscriptionId(), destination);
                return result("FAILED", "FCM_UNREGISTERED");
            }
            if (response.status() == 429 || response.status() >= 500) {
                Instant now = Instant.now();
                long minimum = response.status() == 429 ? 60 : 10;
                Instant after = retryAfter(response.retryAfter(), now, minimum);
                return new DeliveryResult("RETRYABLE", "FCM_TEMPORARY_FAILURE", null, after);
            }
            return result("FAILED", "FCM_REJECTED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result("RETRYABLE", "FCM_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            return result("RETRYABLE", "FCM_TRANSPORT_FAILURE");
        }
    }

    private boolean isUnregistered(Response response) throws IOException {
        if (response.status() != 404 || response.body() == null) return false;
        JsonNode details = json.readTree(response.body()).path("error").path("details");
        if (details.isArray()) for (JsonNode detail : details) {
            if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asText())
                    && "UNREGISTERED".equals(detail.path("errorCode").asText())) return true;
        }
        return false;
    }

    private static Instant retryAfter(String header, Instant now, long minimumSeconds) {
        Instant minimum = now.plusSeconds(minimumSeconds);
        if (header == null || header.isBlank()) return minimum;
        try {
            Instant parsed;
            try { parsed = now.plusSeconds(Long.parseLong(header.trim())); }
            catch (NumberFormatException ex) { parsed = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            return parsed.isAfter(minimum) ? parsed : minimum;
        } catch (RuntimeException ex) { return minimum; }
    }

    private static DeliveryResult result(String status, String code) { return new DeliveryResult(status, code, null); }

    static void requireProjectId(String projectId) {
        if (projectId == null || !projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            throw new IllegalArgumentException("Valid Firebase project ID is required");
        }
    }

    private static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (endpoint, authorization, body) -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
        };
    }
}
