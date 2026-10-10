package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSnapshot;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingPreviewUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogSkuUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogVariantUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.SkuIdentifierResolutionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.CatalogSkuService;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogPricingAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogProductAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogPromotionAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogTaxonomyAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogVariantAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcCatalogPricingPreviewAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcSkuIdentifierResolutionQuery;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPricingPreviewService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPricingService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogProductService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPromotionService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogSkuServiceFacade;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogTaxonomyService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogVariantService;
import com.nexa.api.catalogcommercialpolicy.application.service.SkuIdentifierResolutionService;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogItemSnapshotQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Composes the existing BC-03 services and adapters on one verified Tenant JDBC session. */
public final class TenantBoundCatalogCommercialPolicyRequestAdapter
        implements TenantCatalogCommercialPolicyRequestPort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantCatalogAvailabilityAdapterFactory availabilityAdapters;
    private final TenantCatalogDetailQueryFactory catalogDetails;
    private final TenantCatalogItemSnapshotQueryFactory catalogSnapshots;
    private final RegionalCurrencyQuery regionalCurrency;
    private final CatalogAuthorizationPort catalogAuthorization;
    private final Clock clock;

    public TenantBoundCatalogCommercialPolicyRequestAdapter(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts, TenantSellableSkuQueryFactory sellableSkus,
            TenantCatalogAvailabilityAdapterFactory availabilityAdapters,
            TenantCatalogDetailQueryFactory catalogDetails,
            TenantCatalogItemSnapshotQueryFactory catalogSnapshots,
            RegionalCurrencyQuery regionalCurrency, CatalogAuthorizationPort catalogAuthorization, Clock clock) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts, "Tenant Customer Account factory is required");
        this.sellableSkus = Objects.requireNonNull(sellableSkus, "Tenant Sellable SKU factory is required");
        this.availabilityAdapters = Objects.requireNonNull(availabilityAdapters,
                "Tenant Catalog Availability factory is required");
        this.catalogDetails = Objects.requireNonNull(catalogDetails, "Tenant Catalog detail factory is required");
        this.catalogSnapshots = Objects.requireNonNull(catalogSnapshots, "Tenant Catalog snapshot factory is required");
        this.regionalCurrency = Objects.requireNonNull(regionalCurrency, "Regional currency query is required");
        this.catalogAuthorization = Objects.requireNonNull(catalogAuthorization, "Catalog authorization is required");
        this.clock = Objects.requireNonNull(clock, "Catalog clock is required");
    }

    @Override
    public <T> T inRequest(CurrentAccessContext accessContext, Work<T> work) {
        Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
        Objects.requireNonNull(work, "Tenant Catalog work is required");
        UUID tenantId = accessContext.tenantId().value();
        Optional<String> regionalCurrencySnapshot = regionalCurrency.findCurrency(tenantId);
        return router.inTransaction(accessContext, tenantJdbc -> work.execute(bind(
                accessContext, tenantJdbc, regionalCurrencySnapshot)));
    }

    private Request bind(CurrentAccessContext accessContext, JdbcTemplate tenantJdbc,
            Optional<String> regionalCurrencySnapshot) {
        UUID tenantId = accessContext.tenantId().value();
        UUID workspaceId = accessContext.workspaceId().value();
        CustomerAccountQuery tenantCustomerAccounts = Objects.requireNonNull(customerAccounts.bindTo(tenantJdbc),
                "Tenant Customer Account factory returned no query");
        CatalogClientAccountPort tenantClientAccounts = new CatalogClientAccountCompositionAdapter(
                tenantCustomerAccounts);
        var tenantSellableSkus = Objects.requireNonNull(sellableSkus.bindTo(tenantJdbc, tenantClientAccounts),
                "Tenant Sellable SKU factory returned no query");
        ProductAvailabilityPort tenantAvailability = Objects.requireNonNull(
                availabilityAdapters.bindTo(tenantJdbc, tenantSellableSkus),
                "Tenant Catalog Availability factory returned no adapter");

        var taxonomy = new CatalogTaxonomyService(new JdbcCatalogTaxonomyAdapter(tenantJdbc), catalogAuthorization);
        var products = new CatalogProductService(new JdbcCatalogProductAdapter(tenantJdbc), catalogAuthorization);
        RegionalCurrencyQuery currencySnapshot = requestedTenantId -> {
            if (!tenantId.equals(requestedTenantId)) {
                throw new IllegalArgumentException("Catalog pricing currency scope does not match request Tenant");
            }
            return regionalCurrencySnapshot;
        };
        var pricing = new CatalogPricingService(
                new JdbcCatalogPricingAdapter(tenantJdbc, currencySnapshot), catalogAuthorization, clock);
        var pricingPreview = new CatalogPricingPreviewService(
                new JdbcCatalogPricingPreviewAdapter(tenantJdbc), catalogAuthorization, clock);
        var promotions = new CatalogPromotionService(
                new JdbcCatalogPromotionAdapter(tenantJdbc, tenantClientAccounts), catalogAuthorization);
        CatalogSkuService catalogSkuPort = new CatalogSkuService(tenantJdbc, tenantAvailability);
        var variants = new CatalogVariantService(
                new JdbcCatalogVariantAdapter(tenantJdbc, catalogSkuPort), catalogAuthorization);
        var skus = new CatalogSkuServiceFacade(catalogSkuPort, catalogAuthorization, clock);
        var skuIdentifiers = new SkuIdentifierResolutionService(
                new JdbcSkuIdentifierResolutionQuery(tenantJdbc), catalogAuthorization);
        GetCatalogItemSnapshotUseCase snapshots = Objects.requireNonNull(
                catalogSnapshots.bindTo(tenantJdbc, tenantClientAccounts, tenantAvailability),
                "Tenant Catalog snapshot factory returned no query");
        return new RequestAdapter(accessContext, tenantJdbc, tenantClientAccounts,
                taxonomy, products, pricing, pricingPreview, promotions, variants, skus, skuIdentifiers, snapshots);
    }

    private final class RequestAdapter implements TenantCatalogCommercialPolicyRequestPort.Request {
        private final CurrentAccessContext accessContext;
        private final JdbcTemplate tenantJdbc;
        private final CatalogClientAccountPort tenantClientAccounts;
        private final CatalogTaxonomyUseCase taxonomy;
        private final CatalogProductUseCase products;
        private final CatalogPricingUseCase pricing;
        private final CatalogPricingPreviewUseCase pricingPreview;
        private final CatalogPromotionUseCase promotions;
        private final CatalogVariantUseCase variants;
        private final CatalogSkuUseCase skus;
        private final SkuIdentifierResolutionUseCase skuIdentifiers;
        private final GetCatalogItemSnapshotUseCase snapshots;

        private RequestAdapter(CurrentAccessContext accessContext, JdbcTemplate tenantJdbc,
                CatalogClientAccountPort tenantClientAccounts,
                CatalogTaxonomyUseCase taxonomy, CatalogProductUseCase products, CatalogPricingUseCase pricing,
                CatalogPricingPreviewUseCase pricingPreview, CatalogPromotionUseCase promotions,
                CatalogVariantUseCase variants, CatalogSkuUseCase skus,
                SkuIdentifierResolutionUseCase skuIdentifiers, GetCatalogItemSnapshotUseCase snapshots) {
            this.accessContext = accessContext;
            this.tenantJdbc = tenantJdbc;
            this.tenantClientAccounts = tenantClientAccounts;
            this.taxonomy = taxonomy;
            this.products = products;
            this.pricing = pricing;
            this.pricingPreview = pricingPreview;
            this.promotions = promotions;
            this.variants = variants;
            this.skus = skus;
            this.skuIdentifiers = skuIdentifiers;
            this.snapshots = snapshots;
        }

        @Override
        public CatalogScope scope() {
            return new CatalogScope(accessContext.tenantId().value(), accessContext.workspaceId().value());
        }

        @Override
        public CatalogScope readScope() {
            if (!accessContext.hasRole(MembershipRole.BUYER)) return scope();
            CatalogClientAccountPort.ClientAccountProfile profile = tenantClientAccounts.findProfileForMembership(
                    accessContext.tenantId().value(), accessContext.workspaceId().value(),
                    accessContext.membershipId().value()).orElseThrow(
                            () -> new AccessDeniedException("Active Buyer relationship is required"));
            return new CatalogScope(accessContext.tenantId().value(), accessContext.workspaceId().value(), true,
                    profile.id(), profile.segment(), profile.buyerTier());
        }

        @Override public CatalogTaxonomyUseCase taxonomy() { return taxonomy; }
        @Override public CatalogProductUseCase products() { return products; }
        @Override public CatalogPricingUseCase pricing() { return pricing; }
        @Override public CatalogPricingPreviewUseCase pricingPreview() { return pricingPreview; }
        @Override public CatalogPromotionUseCase promotions() { return promotions; }
        @Override public CatalogVariantUseCase variants() { return variants; }
        @Override public CatalogSkuUseCase skus() { return skus; }
        @Override public SkuIdentifierResolutionUseCase skuIdentifiers() { return skuIdentifiers; }

        @Override
        public TenantCatalogCommercialPolicyRequestPort.CustomerOffer customerOffer(UUID clientAccountId,
                String catalogItemId, java.math.BigDecimal quantity) {
            catalogDetails.requireCatalogRead();
            CatalogClientAccountPort.ClientAccountProfile profile = tenantClientAccounts.findActiveProfile(
                    accessContext.tenantId().value(), accessContext.workspaceId().value(), clientAccountId)
                    .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
            var detailQuery = catalogDetails.bindTo(tenantJdbc, profile, availabilityAdapters::bindTo);
            CatalogItemDetail product = detailQuery.getByCatalogItemId(accessContext.tenantId().value(),
                    accessContext.workspaceId().value(), catalogItemId);
            var quote = snapshots.findActive(catalogItemId, accessContext.tenantId().value(),
                    accessContext.workspaceId().value(), clientAccountId, quantity);
            return new TenantCatalogCommercialPolicyRequestPort.CustomerOffer(profile, product, quote);
        }
    }
}
