package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Current read-only dispatch preparation contract for Operations Mobile. */
@RestController
@Validated
@Profile("!test")
@RequestMapping("/api/v1/dispatch-readiness")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public class DispatchReadinessController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final DispatchReadinessService service;

    public DispatchReadinessController(DispatchReadinessService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listDispatchReadiness")
    public ResponseEntity<DispatchReadinessModels.Page> list(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(context, page, size));
    }

    @GetMapping("/{fulfillmentId}")
    @Operation(operationId = "getDispatchReadiness")
    public ResponseEntity<Readiness> get(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.readiness(context, fulfillmentId));
    }
}
