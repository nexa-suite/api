package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementResult;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishedInstruction;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryInstructionService;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Protected delivery-instruction routes for dispatch and assigned Drivers. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class DeliveryInstructionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final DeliveryInstructionService service;

    public DeliveryInstructionController(DeliveryInstructionService service) {
        this.service = service;
    }

    @GetMapping("/driver/deliveries/{deliveryId}/instructions")
    @Operation(operationId = "getCurrentDriverDeliveryInstructions")
    public ResponseEntity<InstructionSetView> getForDriver(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId) {
        InstructionSetView value = service.getForDriver(context, deliveryId);
        return ResponseEntity.ok().eTag(etag(value.instructionSetVersion())).body(value);
    }

    @PostMapping("/driver/deliveries/{deliveryId}/instruction-acknowledgements")
    @Operation(operationId = "acknowledgeCurrentDriverDeliveryInstructions")
    public ResponseEntity<AcknowledgementResult> acknowledge(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AcknowledgeInstructionsRequest request) {
        AcknowledgementResult value = service.acknowledge(context, deliveryId, version(ifMatch),
                request.instructionIds(), key);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.instructionSetVersion())).body(value);
    }

    @PostMapping("/deliveries/{deliveryId}/instructions")
    @Operation(operationId = "publishOperationalDeliveryInstruction")
    public ResponseEntity<PublishedInstruction> publish(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PublishInstructionRequest request) {
        PublishedInstruction value = service.publish(context, deliveryId, version(ifMatch),
                request.instructionId(), request.kind(), request.content(), key);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    public record AcknowledgeInstructionsRequest(@NotEmpty @Size(max = 100)
                                                List<@NotNull UUID> instructionIds) { }

    public record PublishInstructionRequest(UUID instructionId,
                                            @NotNull DeliveryInstructionKind kind,
                                            @NotBlank @Size(max = 2000) String content) { }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "PRECONDITION_REQUIRED", false);
        }
        String candidate = value.trim();
        if (candidate.startsWith("W/")) throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                "VERSION_INVALID", false);
        if (candidate.length() < 2 || candidate.charAt(0) != '"' || candidate.charAt(candidate.length() - 1) != '"') {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "VERSION_INVALID", false);
        }
        try {
            long parsed = Long.parseLong(candidate.substring(1, candidate.length() - 1));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "VERSION_INVALID", false);
        }
    }
}
