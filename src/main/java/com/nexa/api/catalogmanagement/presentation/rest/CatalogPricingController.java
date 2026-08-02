package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPricingUseCase;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public CatalogPricingController(CatalogPricingUseCase pricing) { this.pricing = pricing; }

    @GetMapping("/products/{productId}/prices")
    public List<CatalogManagementModels.PriceView> history(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID productId) { return pricing.history(CatalogHttpSupport.scope(context), productId); }

    @PostMapping("/products/{productId}/prices")
    public ResponseEntity<CatalogManagementModels.PriceView> create(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID productId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PriceRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = pricing.create(CatalogHttpSupport.scope(context), productId, request.amount(), request.currency(), request.validFrom(), request.validUntil(), request.sourceCode(), request.sourceDescription(), idempotencyKey);
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PostMapping("/prices/{priceId}/cancellations")
    public ResponseEntity<CatalogManagementModels.PriceView> cancel(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID priceId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = pricing.cancel(CatalogHttpSupport.scope(context), priceId, CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    public record PriceRequest(BigDecimal amount, String currency, Instant validFrom, Instant validUntil,
            String sourceCode, String sourceDescription) { }
}
