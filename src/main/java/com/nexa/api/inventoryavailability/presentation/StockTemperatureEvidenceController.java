package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.service.StockTemperatureEvidenceService;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.StockTemperatureEvidence;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/temperature-evidence")
@Profile("!test")
@Tag(name = "Inventory Temperature Evidence")
@SecurityRequirement(name = "bearerAuth")
public final class StockTemperatureEvidenceController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final StockTemperatureEvidenceService service;

    public StockTemperatureEvidenceController(StockTemperatureEvidenceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "recordStockTemperatureEvidence",
            description = "Records a manual stock temperature reading as pending confirmation. This operation does not change inventory disposition.")
    public TemperatureEvidenceResponse record(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Parameter(description = "Stable key for safe retry of the same evidence", required = true)
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TemperatureEvidenceRequest request) {
        StockTemperatureEvidence evidence = service.record(context, request.lotId(), request.warehouseId(),
                request.value(), request.unit(), request.observedAt(), idempotencyKey);
        return response(evidence);
    }

    private static TemperatureEvidenceResponse response(StockTemperatureEvidence evidence) {
        return new TemperatureEvidenceResponse(evidence.id(), evidence.subjectType().name(), evidence.lotId(),
                evidence.warehouseId(), evidence.value(), evidence.unit().name(), evidence.observedAt(),
                evidence.recordedAt(), evidence.actorMembershipId(), evidence.status().name());
    }

    @Schema(name = "StockTemperatureEvidenceRequest", description = "One manual reading for one known lot or warehouse.")
    public record TemperatureEvidenceRequest(
            @Schema(description = "Lot being measured. Supply this or warehouseId, but not both.") UUID lotId,
            @Schema(description = "Warehouse being measured. Supply this or lotId, but not both.") UUID warehouseId,
            @NotNull @Schema(description = "Measured value in the supplied unit") BigDecimal value,
            @NotBlank @Schema(allowableValues = {"CELSIUS", "FAHRENHEIT"}) String unit,
            @NotNull @Schema(description = "Time the physical reading was observed") Instant observedAt) { }

    @Schema(name = "StockTemperatureEvidenceResponse")
    public record TemperatureEvidenceResponse(UUID id, String subjectType, UUID lotId, UUID warehouseId,
                                              BigDecimal value, String unit, Instant observedAt,
                                              Instant recordedAt, UUID actorMembershipId, String status) { }
}
