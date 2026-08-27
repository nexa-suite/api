package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingPreviewUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogClientAccountPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/catalog/pricing-preview")
@Tag(name = "Catalog Pricing")
@SecurityRequirement(name = "bearerAuth")
public final class CatalogPricingPreviewController {
    private final CatalogPricingPreviewUseCase pricing;
    private final ObjectProvider<CatalogClientAccountPort> clientAccounts;

    public CatalogPricingPreviewController(CatalogPricingPreviewUseCase pricing, ObjectProvider<CatalogClientAccountPort> clientAccounts) {
        this.pricing = pricing;
        this.clientAccounts = clientAccounts;
    }

    @PostMapping
    @Operation(summary = "Preview tenant-scoped effective catalog pricing by quantity")
    public ResponseEntity<Response> preview(
            @RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @Valid @RequestBody Request request) {
        CatalogPricingPreviewModels.Request input = new CatalogPricingPreviewModels.Request(
                request.items().stream().map(item -> new CatalogPricingPreviewModels.ItemRequest(item.productId(), item.quantity())).toList(), request.asOf());
        return ResponseEntity.ok(Response.from(pricing.preview(CatalogHttpSupport.scope(context, clientAccounts), input)));
    }

    public record Request(List<Item> items, Instant asOf) {
        public Request { items = items == null ? List.of() : List.copyOf(items); }
    }
    public record Item(UUID productId, BigDecimal quantity) { }
    public record Response(List<ItemResponse> items) {
        static Response from(CatalogPricingPreviewModels.Result result) {
            return new Response(result.items().stream().map(ItemResponse::from).toList());
        }
    }
    public record ItemResponse(String productId, BigDecimal quantity, MoneyResponse baseUnitPrice,
            MoneyResponse effectiveUnitPrice, MoneyResponse lineBaseTotal, MoneyResponse lineEffectiveTotal,
            MoneyResponse discountAmount, String currency, List<AppliedPromotionResponse> appliedPromotions, Instant pricingAsOf) {
        static ItemResponse from(CatalogPricingPreviewModels.ItemResult item) {
            return new ItemResponse(item.productId().toString(), item.quantity(), money(item.baseUnitPrice(), item.currency()),
                    money(item.effectiveUnitPrice(), item.currency()), money(item.lineBaseTotal(), item.currency()),
                    money(item.lineEffectiveTotal(), item.currency()), money(item.discountAmount(), item.currency()), item.currency(),
                    item.appliedPromotions().stream().map(value -> new AppliedPromotionResponse(value.id().toString(), value.name(), value.discountType(), money(value.discountAmount(), item.currency()))).toList(),
                    item.pricingAsOf());
        }
        private static MoneyResponse money(BigDecimal amount, String currency) { return amount == null ? null : new MoneyResponse(amount, currency); }
    }
    public record MoneyResponse(BigDecimal amount, String currency) { }
    public record AppliedPromotionResponse(String id, String name, String discountType, MoneyResponse discountAmount) { }
}
