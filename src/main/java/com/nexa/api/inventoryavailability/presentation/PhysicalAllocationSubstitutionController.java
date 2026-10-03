package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests;
import com.nexa.api.inventoryavailability.application.service.PhysicalAllocationSubstitutionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** BC-05 endpoint for a reasoned substitution request; it does not mutate an allocation. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class PhysicalAllocationSubstitutionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final PhysicalAllocationSubstitutionService service;

    public PhysicalAllocationSubstitutionController(PhysicalAllocationSubstitutionService service) {
        this.service = service;
    }

    @PostMapping("/inventory/physical-allocation-substitution-requests")
    @Operation(operationId = "requestPhysicalAllocationLotSubstitution")
    public ResponseEntity<SubstitutionRequestResponse> request(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubstitutionRequestBody request) {
        long expectedVersion = version(ifMatch);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
        PhysicalAllocationSubstitutionRequests.Result result = service.request(
                context, request.fulfillmentId(), request.allocationId(), request.physicalAllocationLineId(),
                request.expectedLotId(), request.alternativeLotId(), request.quantity(), request.unit(),
                request.reason(), idempotencyKey, expectedVersion);
        var fact = result.fact();
        SubstitutionRequestResponse response = new SubstitutionRequestResponse(
                fact.id(), fact.expectedLotId(), fact.alternativeLotId(), fact.quantity(), fact.reason(),
                fact.status(), fact.currentAllocationVersion());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(etag(fact.currentAllocationVersion())).body(response);
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) {
            throw new WarehouseOperationsService.WarehouseException("PRECONDITION_REQUIRED", false);
        }
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

    public record SubstitutionRequestBody(@NotNull UUID fulfillmentId,
                                          @NotNull UUID allocationId,
                                          @NotNull UUID physicalAllocationLineId,
                                          @NotNull UUID expectedLotId,
                                          @NotNull UUID alternativeLotId,
                                          @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal quantity,
                                          @NotBlank @Size(max = 32) String unit,
                                          @NotBlank @Size(max = 2_000) String reason) { }

    public record SubstitutionRequestResponse(UUID id, UUID expectedLotId, UUID alternativeLotId,
                                              BigDecimal quantity, String reason, String status,
                                              long currentAllocationVersion) { }
}
