package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Captures attributable receiving facts and submits them for review only after evidence is available. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/inventory/inbound-discrepancy-cases")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class InboundReceivingDiscrepancyController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final WarehouseAuxiliaryOperationsRequestRunner operations;

    public InboundReceivingDiscrepancyController(WarehouseAuxiliaryOperationsRequestRunner operations) {
        this.operations = operations;
    }

    @PostMapping
    @Operation(operationId = "recordInboundReceivingDiscrepancy")
    public ResponseEntity<CaseResponse> create(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCaseBody body) {
        requireKey(idempotencyKey);
        var fact = operations.execute(context, services -> services.receivingDiscrepancies().create(
                context, body.warehouseId(), body.expectedSkuId(), body.observedSkuId(),
                body.expectedBatchReference(), body.observedBatchReference(), body.expectedQuantity(),
                body.observedQuantity(), body.unit(), body.reason(), body.observationNotes(), idempotencyKey));
        return ResponseEntity.status(fact.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(etag(fact.version())).body(response(fact));
    }

    @PostMapping("/{caseId}/submissions")
    @Operation(operationId = "submitInboundReceivingDiscrepancyForReview")
    public ResponseEntity<CaseResponse> submit(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID caseId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitCaseBody body) {
        requireKey(idempotencyKey);
        long expectedVersion = version(ifMatch);
        var fact = operations.execute(context, services -> services.receivingDiscrepancies().submit(
                context, caseId, body.evidenceObjectId(), expectedVersion, idempotencyKey));
        return ResponseEntity.status(fact.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(etag(fact.version())).body(response(fact));
    }

    private static CaseResponse response(InboundReceivingDiscrepancyCases.CaseFact fact) {
        return new CaseResponse(fact.id(), fact.warehouseId(), fact.expectedSkuId(), fact.observedSkuId(),
                fact.expectedBatchReference(), fact.observedBatchReference(), fact.expectedQuantity(),
                fact.observedQuantity(), fact.unit(), fact.reason(), fact.observationNotes(), fact.status(),
                fact.evidenceObjectId(), fact.version(), fact.recordedByMembershipId(), fact.recordedAt(),
                fact.submittedByMembershipId(), fact.submittedAt());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 160) {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        String candidate = value.trim();
        if (candidate.length() < 3 || candidate.charAt(0) != '"' || candidate.charAt(candidate.length() - 1) != '"') {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
        try {
            long parsed = Long.parseLong(candidate.substring(1, candidate.length() - 1));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    public record CreateCaseBody(@NotNull UUID warehouseId,
                                 UUID expectedSkuId,
                                 @NotNull UUID observedSkuId,
                                 @Size(max = 160) String expectedBatchReference,
                                 @Size(max = 160) String observedBatchReference,
                                 @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal expectedQuantity,
                                 @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal observedQuantity,
                                 @NotBlank @Size(max = 32) String unit,
                                 @NotBlank @Size(max = 2_000) String reason,
                                 @Size(max = 2_000) String observationNotes) { }

    public record SubmitCaseBody(@NotNull UUID evidenceObjectId) { }

    public record CaseResponse(UUID id, UUID warehouseId, UUID expectedSkuId, UUID observedSkuId,
                               String expectedBatchReference, String observedBatchReference,
                               BigDecimal expectedQuantity, BigDecimal observedQuantity, String unit,
                               String reason, String observationNotes, String status, UUID evidenceObjectId,
                               long version, UUID recordedByMembershipId, Instant recordedAt,
                               UUID submittedByMembershipId, Instant submittedAt) { }
}
