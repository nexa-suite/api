package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.port.in.SkuIdentifierResolutionUseCase;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated additive endpoint for the physical identifier owned by BC-03. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Product Families and Sellable SKUs")
@SecurityRequirement(name = "bearerAuth")
public final class SkuIdentifierResolutionController {
    private final SkuIdentifierResolutionUseCase service;
    private final ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests;

    public SkuIdentifierResolutionController(SkuIdentifierResolutionUseCase service) {
        this(service, null);
    }

    @Autowired
    public SkuIdentifierResolutionController(SkuIdentifierResolutionUseCase service,
            ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests) {
        this.service = service;
        this.tenantRequests = tenantRequests;
    }

    @GetMapping("/skus/resolve")
    @Operation(operationId = "resolveSellableSkuIdentifier")
    public SkuIdentifierResolutionUseCase.Resolution resolve(
            @RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam String identifier) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> service.resolve(CatalogHttpSupport.scope(context), identifier),
                request -> request.skuIdentifiers().resolve(request.scope(), identifier));
    }
}
