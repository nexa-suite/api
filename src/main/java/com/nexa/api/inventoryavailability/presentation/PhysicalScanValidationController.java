package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.service.PhysicalScanValidationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Additive BC-05 contract for validating a physical picking scan. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class PhysicalScanValidationController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final PhysicalScanValidationService service;

    public PhysicalScanValidationController(PhysicalScanValidationService service) {
        this.service = service;
    }

    @PostMapping("/inventory/physical-allocation-scan-validations")
    @Operation(operationId = "validatePhysicalAllocationPickingScan")
    public PhysicalAllocationCommands.PickingScanValidationResult validate(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Valid @RequestBody ScanRequest request) {
        return service.validate(context, new PhysicalAllocationCommands.PickingScanValidationRequest(
                context.tenantId().value(), context.workspaceId().value(), request.fulfillmentId(),
                request.physicalAllocationLineId(), request.skuId(), request.lotId(), request.warehouseId(),
                request.quantity(), request.unit(), request.allocationVersion(), java.time.Instant.now(),
                context.membershipId().value(), false, null));
    }

    public record ScanRequest(@NotNull UUID fulfillmentId, @NotNull UUID physicalAllocationLineId,
                              @NotNull UUID skuId, @NotNull UUID lotId, @NotNull UUID warehouseId,
                              @NotNull @Positive BigDecimal quantity, @NotBlank String unit,
                              @NotNull @PositiveOrZero Long allocationVersion) { }
}
