package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog Pricing")
@SecurityRequirement(name = "bearerAuth")
public final class CatalogPricingController {
    private final CatalogPricingUseCase pricing;
    private final ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests;

    public CatalogPricingController(CatalogPricingUseCase pricing) { this(pricing, null); }

    @Autowired
    public CatalogPricingController(CatalogPricingUseCase pricing,
            ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests) {
        this.pricing = pricing;
        this.tenantRequests = tenantRequests;
    }

    @GetMapping("/products/{productId}/prices")
    @Operation(operationId = "listDeprecatedProductPriceProjection", deprecated = true)
    public List<CatalogManagementModels.PriceView> history(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID productId) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> pricing.history(CatalogHttpSupport.scope(context), productId),
                request -> request.pricing().history(request.scope(), productId));
    }

}
