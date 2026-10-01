package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.service.OperationalExceptionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Assigned-driver reads and append-only claim/review commands for operational exceptions. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class OperationalExceptionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final OperationalExceptionService service;

    public OperationalExceptionController(OperationalExceptionService service) {
        this.service = service;
    }

    @GetMapping("/driver/deliveries/{deliveryId}/operational-exceptions")
    @Operation(operationId = "getCurrentDriverDeliveryOperationalExceptions")
    public ResponseEntity<ExceptionSetView> getForDriver(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId) {
        ExceptionSetView value = service.getForDriver(context, deliveryId);
        return ResponseEntity.ok().eTag(etag(value.deliveryVersion())).body(value);
    }

    @PostMapping("/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/claims")
    @Operation(operationId = "claimCurrentDriverDeliveryOperationalException")
    public ResponseEntity<MutationResult> claim(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @PathVariable UUID exceptionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        MutationResult value = service.claim(context, deliveryId, exceptionId, version(ifMatch), idempotencyKey);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    @PostMapping("/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/reviews")
    @Operation(operationId = "startCurrentDriverDeliveryOperationalExceptionReview")
    public ResponseEntity<MutationResult> review(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @PathVariable UUID exceptionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        MutationResult value = service.review(context, deliveryId, exceptionId, version(ifMatch), idempotencyKey);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw error("PRECONDITION_REQUIRED");
        String candidate = value.trim();
        if (candidate.startsWith("W/") || candidate.length() < 2 || candidate.charAt(0) != '"'
                || candidate.charAt(candidate.length() - 1) != '"') throw error("VERSION_INVALID");
        try {
            long parsed = Long.parseLong(candidate.substring(1, candidate.length() - 1));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw error("VERSION_INVALID");
        }
    }

    private static com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException error(String code) {
        return new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(code, false);
    }
}
