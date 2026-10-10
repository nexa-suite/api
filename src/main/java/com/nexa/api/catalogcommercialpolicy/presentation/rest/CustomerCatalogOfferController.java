package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSnapshot;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.mapper.CatalogResponseMapper;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogItemDetailResponse;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Internal field reads use the same customer- and quantity-specific offer as Sales submission. */
@RestController
@Profile("!test")
@Validated
@RequestMapping("/api/v1/client-accounts/{clientAccountId}/catalog-offers")
@Tag(name = "Catalog Pricing")
@SecurityRequirement(name = "bearerAuth")
public class CustomerCatalogOfferController {
    private final CatalogClientAccountPort accounts;
    private final GetCatalogItemUseCase products;
    private final GetCatalogItemSnapshotUseCase snapshots;
    private final CatalogResponseMapper mapper;
    private final Clock clock;
    private final ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests;
    public CustomerCatalogOfferController(CatalogClientAccountPort accounts, GetCatalogItemUseCase products,
            GetCatalogItemSnapshotUseCase snapshots, CatalogResponseMapper mapper, Clock clock) {
        this(accounts, products, snapshots, mapper, clock, null);
    }

    @Autowired
    public CustomerCatalogOfferController(CatalogClientAccountPort accounts, GetCatalogItemUseCase products,
            GetCatalogItemSnapshotUseCase snapshots, CatalogResponseMapper mapper, Clock clock,
            ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests) {
        this.accounts = accounts; this.products = products; this.snapshots = snapshots; this.mapper = mapper; this.clock = clock;
        this.tenantRequests = tenantRequests;
    }
    @GetMapping("/{catalogItemId}")
    @Operation(operationId = "getCustomerCatalogOffer")
    public OfferResponse get(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID clientAccountId,
            @PathVariable @Pattern(regexp = "(?i)CAT-[A-Z0-9-]{1,63}") String catalogItemId,
            @RequestParam(defaultValue = "1") @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Internal customer catalog access is unavailable to buyers");
        context.requirePermission(PermissionKey.CLIENT_READ);
        context.requirePermission(PermissionKey.CATALOG_READ);
        OfferParts offer = CatalogHttpSupport.tenantRequest(context, tenantRequests, () -> {
            var profile = accounts.findActiveProfile(context.tenantId().value(), context.workspaceId().value(), clientAccountId)
                    .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
            var scope = new CatalogScope(context.tenantId().value(), context.workspaceId().value(), false,
                    profile.id(), profile.segment(), profile.buyerTier());
            var product = products.getByCatalogItemId(scope, catalogItemId);
            var quote = snapshots.findActive(catalogItemId, context.tenantId().value(), context.workspaceId().value(), clientAccountId, quantity)
                    .orElseThrow(() -> new ApiResourceNotFoundException("catalog-item"));
            return new OfferParts(product, quote);
        }, request -> {
            var tenantOffer = request.customerOffer(clientAccountId, catalogItemId, quantity);
            var quote = tenantOffer.quote().orElseThrow(() -> new ApiResourceNotFoundException("catalog-item"));
            return new OfferParts(tenantOffer.product(), quote);
        });
        var product = mapper.toDetail(offer.product());
        var quote = offer.quote();
        return new OfferResponse(clientAccountId.toString(), product, quantity,
                new MoneyResponse(quote.unitPriceAmount(), quote.unitPriceCurrency()), clock.instant());
    }
    private record OfferParts(CatalogItemDetail product, CatalogItemSnapshot quote) { }
    public record MoneyResponse(BigDecimal amount, String currency) { }
    public record OfferResponse(String clientAccountId, CatalogItemDetailResponse product, BigDecimal quantity,
                                MoneyResponse unitPrice, Instant asOf) { }
}
