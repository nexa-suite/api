package com.nexa.api.notifications.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.nexa.api.notifications.application.port.out.PushProviderPort.Delivery;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FcmPushProviderTests {
    private final ObjectMapper json = new ObjectMapper();
    private final Delivery delivery = new Delivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "event-1", "EVENT", "DELIVERY", "private title", "private body", "/private", "key");

    @Test void acceptedSendsOnlyWakeupHint() {
        var provider = new FcmPushProvider("nexa-project", (tenant, workspace, id) -> "device-token", (t, w, id, token) -> {}, () -> "access-token", json,
                (uri, auth, body) -> {
                    assertEquals("https://fcm.googleapis.com/v1/projects/nexa-project/messages:send", uri.toString());
                    assertEquals("Bearer access-token", auth);
                    assertTrue(body.contains("event-1"));
                    assertFalse(body.contains("private title"));
                    assertFalse(body.contains("private body"));
                    assertFalse(body.contains("/private"));
                    var payload = json.readTree(body).path("message");
                    assertEquals("Nexa", payload.path("notification").path("title").asText());
                    assertEquals("Hay una actualización disponible. Abre Nexa para revisarla.",
                            payload.path("notification").path("body").asText());
                    assertEquals("nexa_notifications", payload.path("android").path("notification")
                            .path("channel_id").asText());
                    assertEquals(1, payload.path("data").size());
                    assertEquals("event-1", payload.path("data").path("eventId").asText());
                    return new FcmPushProvider.Response(200, "{}");
                });
        assertEquals("SENT", provider.deliver(delivery).status());
    }

    @Test void missingDestinationOrCredentialsNeverSend() {
        AtomicInteger sent = new AtomicInteger();
        FcmPushProvider.Sender sender = (uri, auth, body) -> { sent.incrementAndGet(); return new FcmPushProvider.Response(200, "{}"); };
        assertEquals("DEFERRED", new FcmPushProvider("nexa-project", (tenant, workspace, id) -> null, (t, w, id, token) -> {}, () -> "token", json, sender).deliver(delivery).status());
        assertEquals("DEFERRED", new FcmPushProvider("nexa-project", (tenant, workspace, id) -> "token", (t, w, id, token) -> {}, () -> null, json, sender).deliver(delivery).status());
        assertEquals(0, sent.get());
    }

    @Test void scopeIsPassedToResolverAndMissingScopeFailsClosed() {
        var provider = new FcmPushProvider("nexa-project", (tenant, workspace, id) -> {
            assertEquals(delivery.tenantId(), tenant);
            assertEquals(delivery.workspaceId(), workspace);
            assertEquals(delivery.subscriptionId(), id);
            return "token";
        }, (t, w, id, token) -> {}, () -> "access", json, (uri, auth, body) -> new FcmPushProvider.Response(200, "{}"));
        assertEquals("SENT", provider.deliver(delivery).status());
        var unscoped = new Delivery(UUID.randomUUID(), "event", "EVENT", "DELIVERY", "", "", "", "key");
        assertEquals("SCOPE_UNAVAILABLE", provider.deliver(unscoped).providerCode());
    }

    @Test void distinguishesUnregisteredFromOtherErrors() {
        String invalid = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
        var unregistered = provider(404, invalid).deliver(delivery);
        assertEquals("FAILED", unregistered.status());
        assertEquals("FCM_UNREGISTERED", unregistered.providerCode());
        assertEquals("RETRYABLE", provider(429, "{}").deliver(delivery).status());
        assertEquals("RETRYABLE", provider(503, "{}").deliver(delivery).status());
        assertEquals("FAILED", provider(400, "{}").deliver(delivery).status());
    }

    @Test void unregisteredInvalidatesExactlyTheAttemptedScopedDestination() {
        AtomicInteger invalidated = new AtomicInteger();
        String error = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
        var provider = new FcmPushProvider("nexa-project", (t, w, id) -> "old-token", (t, w, id, token) -> {
            assertEquals(delivery.tenantId(), t);
            assertEquals(delivery.workspaceId(), w);
            assertEquals(delivery.subscriptionId(), id);
            assertEquals("old-token", token);
            invalidated.incrementAndGet();
        }, () -> "access", json, (uri, auth, body) -> new FcmPushProvider.Response(404, error));
        assertEquals("FCM_UNREGISTERED", provider.deliver(delivery).providerCode());
        assertEquals(1, invalidated.get());
    }

    @Test void fcmFidUsesDedicatedTargetAndMalformedPrefixFailsClosed() {
        var fid = new FcmPushProvider("nexa-project", (t, w, id) -> "fcm-fid:abcdefghijk12345678901",
                (t, w, id, token) -> {}, () -> "access", json, (uri, auth, body) -> {
                    assertTrue(body.contains("\"fid\":\"abcdefghijk12345678901\""));
                    assertFalse(body.contains("fcm-fid:"));
                    assertFalse(body.contains("\"token\":"));
                    return new FcmPushProvider.Response(200, "{}");
                });
        assertEquals("SENT", fid.deliver(delivery).status());
        var malformed = new FcmPushProvider("nexa-project", (t, w, id) -> "fcm-fid:",
                (t, w, id, token) -> {}, () -> "access", json, (uri, auth, body) -> { fail("must not send"); return null; });
        assertEquals("FCM_DESTINATION_INVALID", malformed.deliver(delivery).providerCode());
    }

    @Test void retryAfterEnforcesProviderMinimumAndHeader() {
        var start = java.time.Instant.now();
        var provider = new FcmPushProvider("nexa-project", (t, w, id) -> "token", (t, w, id, token) -> {},
                () -> "access", json, (uri, auth, body) -> new FcmPushProvider.Response(429, "{}", "120"));
        assertTrue(!provider.deliver(delivery).retryAfter().isBefore(start.plusSeconds(120)));
        assertTrue(!provider(429, "{}").deliver(delivery).retryAfter().isBefore(start.plusSeconds(60)));
        assertTrue(!provider(503, "{}").deliver(delivery).retryAfter().isBefore(start.plusSeconds(10)));
    }

    @Test void legacyRegistrationTokenUsesTheCompatibilityTargetField() {
        var provider = new FcmPushProvider("nexa-project", (t, w, id) -> "legacy-registration-token",
                (t, w, id, token) -> {}, () -> "access", json, (uri, auth, body) -> {
                    var message = json.readTree(body).path("message");
                    assertEquals("legacy-registration-token", message.path("token").asText());
                    assertFalse(message.has("fid"));
                    return new FcmPushProvider.Response(200, "{}");
                });
        assertEquals("SENT", provider.deliver(delivery).status());
    }

    private FcmPushProvider provider(int status, String body) {
        return new FcmPushProvider("nexa-project", (tenant, workspace, id) -> "token", (t, w, id, token) -> {}, () -> "access", json,
                (uri, auth, payload) -> new FcmPushProvider.Response(status, body));
    }
}
