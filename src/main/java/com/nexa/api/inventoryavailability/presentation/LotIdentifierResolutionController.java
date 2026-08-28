package com.nexa.api.inventoryavailability.presentation;

import com.nexa.api.inventoryavailability.application.service.LotIdentifierResolutionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated additive endpoint for the batch identifier owned by BC-05. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Warehouse Operations")
@SecurityRequirement(name = "bearerAuth")
public final class LotIdentifierResolutionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final LotIdentifierResolutionService service;

    public LotIdentifierResolutionController(LotIdentifierResolutionService service) {
        this.service = service;
    }

    @GetMapping("/inventory/lots/resolve")
    @Operation(operationId = "resolveInventoryLotIdentifier")
    public LotIdentifierResolutionService.Resolution resolve(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam String batchNumber) {
        return service.resolve(context, batchNumber);
    }
}
