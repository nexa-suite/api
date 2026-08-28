package com.nexa.api.notifications.presentation;

import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.notifications.application.service.PushSubscriptionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Additive BC-10 provider-neutral native push subscription contract. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/notifications/push-subscriptions")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public final class PushSubscriptionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final PushSubscriptionService service;

    public PushSubscriptionController(PushSubscriptionService service) { this.service = service; }

    @PostMapping
    @Operation(operationId = "registerNativePushSubscription")
    public ResponseEntity<PushSubscriptionResponse> register(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Parameter(name = "X-Nexa-Client", required = true, description = "Must be NATIVE for native push registration")
            @RequestHeader(name = "X-Nexa-Client", required = false) String nativeClient,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe subscription registration")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201).body(response(service.register(context, nativeClient, request.installationId(),
                request.platform(), request.providerToken(), idempotencyKey)));
    }

    @PostMapping("/{subscriptionId}/disable")
    @Operation(operationId = "disableNativePushSubscription")
    public ResponseEntity<PushSubscriptionResponse> disable(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID subscriptionId,
            @Parameter(name = "X-Nexa-Client", required = true, description = "Must be NATIVE for native push management")
            @RequestHeader(name = "X-Nexa-Client", required = false) String nativeClient,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe subscription disable")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(response(service.disable(context, nativeClient, subscriptionId, idempotencyKey, false)));
    }

    @DeleteMapping("/{subscriptionId}")
    @Operation(operationId = "unregisterNativePushSubscription")
    public ResponseEntity<Void> unregister(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID subscriptionId,
            @Parameter(name = "X-Nexa-Client", required = true, description = "Must be NATIVE for native push management")
            @RequestHeader(name = "X-Nexa-Client", required = false) String nativeClient,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe subscription unregister")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        service.disable(context, nativeClient, subscriptionId, idempotencyKey, true);
        return ResponseEntity.noContent().build();
    }

    private static PushSubscriptionResponse response(PushSubscriptionPersistencePort.PushSubscription value) {
        return new PushSubscriptionResponse(value.id(), value.recipientMembershipId(), value.installationId(),
                value.platform(), value.surface(), value.status(), value.createdAt(), value.updatedAt(), value.version());
    }

    public record RegisterRequest(@NotBlank @Size(max = 160) String installationId,
                                  @NotBlank @Size(max = 16) String platform,
                                  @NotBlank @Size(max = 4096) String providerToken) { }
    public record PushSubscriptionResponse(UUID id, UUID recipientMembershipId, String installationId,
                                           String platform, String surface, String status, Instant createdAt,
                                           Instant updatedAt, long version) { }
}
