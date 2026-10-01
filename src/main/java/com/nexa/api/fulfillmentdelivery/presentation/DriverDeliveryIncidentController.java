package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentView;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryIncidentService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Protected driver commands for additive delivery incident facts. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class DriverDeliveryIncidentController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final DriverDeliveryIncidentService service;

    public DriverDeliveryIncidentController(DriverDeliveryIncidentService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(operationId = "recordCurrentDriverDeliveryIncident")
    public ResponseEntity<IncidentView> record(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                               @PathVariable UUID deliveryId,
                                               @PathVariable UUID attemptId,
                                               @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody RecordIncidentRequest request) {
        IncidentView value = service.record(context, deliveryId, attemptId, version(ifMatch), key,
                request.reason(), request.description(), request.place());
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    @PostMapping("/{incidentId}/evidence")
    @Operation(operationId = "attachCurrentDriverDeliveryIncidentEvidence")
    public ResponseEntity<IncidentView> attachEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                       @PathVariable UUID deliveryId,
                                                       @PathVariable UUID attemptId,
                                                       @PathVariable UUID incidentId,
                                                       @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                       @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody AttachIncidentEvidenceRequest request) {
        IncidentView value = service.attachEvidence(context, deliveryId, attemptId, incidentId,
                version(ifMatch), key, request.evidenceObjectIds());
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    public record RecordIncidentRequest(@NotBlank @Size(max = 500) String reason,
                                        @NotBlank @Size(max = 2000) String description,
                                        @NotBlank @Size(max = 500) String place) { }

    public record AttachIncidentEvidenceRequest(@NotNull @Size(min = 1, max = 16)
                                                List<@NotNull UUID> evidenceObjectIds) { }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw error("PRECONDITION_REQUIRED");
        String candidate = value.trim();
        if (candidate.regionMatches(true, 0, "W/", 0, 2)) candidate = candidate.substring(2).trim();
        try {
            long parsed = Long.parseLong(candidate.replace("\"", ""));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw error("PRECONDITION_INVALID");
        }
    }

    private static com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException error(String code) {
        return new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(code, false);
    }
}
