package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

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
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPricingPreviewService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPricingService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogProductService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogPromotionService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogSkuServiceFacade;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogTaxonomyService;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogVariantService;
import com.nexa.api.catalogcommercialpolicy.application.service.SkuIdentifierResolutionService;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.CatalogSkuService;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogPricingAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogProductAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogPromotionAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogTaxonomyAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.persistence.JdbcCatalogVariantAdapter;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestCompositionFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogItemSnapshotQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Composes all request-local BC-03 services and adapters without owning routing or transaction creation. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogCommercialPolicyRequestCompositionFactory
        implements TenantCatalogCommercialPolicyRequestCompositionFactory {
    private final TenantCatalogDetailQueryFactory catalogDetails;
    private final TenantCatalogItemSnapshotQueryFactory catalogSnapshots;
    private final CatalogAuthorizationPort catalogAuthorization;
    private final Clock clock;

    public JdbcTenantCatalogCommercialPolicyRequestCompositionFactory(
            TenantCatalogDetailQueryFactory catalogDetails,
            TenantCatalogItemSnapshotQueryFactory catalogSnapshots,
            CatalogAuthorizationPort catalogAuthorization, Clock clock) {
        this.catalogDetails = Objects.requireNonNull(catalogDetails);
        this.catalogSnapshots = Objects.requireNonNull(catalogSnapshots);
        this.catalogAuthorization = Objects.requireNonNull(catalogAuthorization);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public TenantCatalogCommercialPolicyRequestPort.Request bindTo(CurrentAccessContext accessContext,
            JdbcTemplate tenantJdbc, CatalogClientAccountPort tenantClientAccounts,
            CatalogAvailabilityQuery tenantAvailability,
            TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory availabilityFactory,
            Optional<String> regionalCurrencySnapshot) {
        CurrentAccessContext context = Objects.requireNonNull(accessContext,
                "Verified Tenant access context is required");
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        CatalogClientAccountPort accounts = Objects.requireNonNull(tenantClientAccounts,
                "Tenant Catalog account port is required");
        CatalogAvailabilityQuery availabilityQuery = Objects.requireNonNull(tenantAvailability,
                "Tenant Catalog availability query is required");
        ProductAvailabilityPort availability = new CatalogAvailabilityPortAdapter(availabilityQuery);
        TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory availabilityBinding =
                Objects.requireNonNull(availabilityFactory, "Tenant availability factory is required");
        Optional<String> currency = Objects.requireNonNull(regionalCurrencySnapshot,
                "Regional currency snapshot is required");
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();

        var taxonomy = new CatalogTaxonomyService(new JdbcCatalogTaxonomyAdapter(jdbc), catalogAuthorization);
        var products = new CatalogProductService(new JdbcCatalogProductAdapter(jdbc), catalogAuthorization);
        RegionalCurrencyQuery currencySnapshot = requestedTenantId -> {
            if (!tenantId.equals(requestedTenantId)) {
                throw new IllegalArgumentException("Catalog pricing currency scope does not match request Tenant");
            }
            return currency;
        };
        var pricing = new CatalogPricingService(
                new JdbcCatalogPricingAdapter(jdbc, currencySnapshot), catalogAuthorization, clock);
        var pricingPreview = new CatalogPricingPreviewService(
                new JdbcCatalogPricingPreviewAdapter(jdbc), catalogAuthorization, clock);
        var promotions = new CatalogPromotionService(
                new JdbcCatalogPromotionAdapter(jdbc, accounts), catalogAuthorization);
        CatalogSkuService catalogSkuPort = new CatalogSkuService(jdbc, availability);
        var catalogVariantPort = new JdbcCatalogVariantAdapter(jdbc, catalogSkuPort);
        var variants = new CatalogVariantService(catalogVariantPort, catalogAuthorization);
        var skus = new CatalogSkuServiceFacade(catalogSkuPort, catalogVariantPort, catalogAuthorization, clock);
        var skuIdentifiers = new SkuIdentifierResolutionService(
                new JdbcSkuIdentifierResolutionQuery(jdbc), catalogAuthorization);
        GetCatalogItemSnapshotUseCase snapshots = Objects.requireNonNull(
                catalogSnapshots.bindTo(jdbc, accounts, availabilityQuery),
                "Tenant Catalog snapshot factory returned no query");
        return new RequestAdapter(context, jdbc, accounts, availabilityBinding,
                taxonomy, products, pricing, pricingPreview, promotions, variants, skus, skuIdentifiers, snapshots);
    }

    private final class RequestAdapter implements TenantCatalogCommercialPolicyRequestPort.Request {
        private final CurrentAccessContext accessContext;
        private final JdbcTemplate tenantJdbc;
        private final CatalogClientAccountPort tenantClientAccounts;
        private final TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory availabilityFactory;
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
                TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory availabilityFactory,
                CatalogTaxonomyUseCase taxonomy, CatalogProductUseCase products, CatalogPricingUseCase pricing,
                CatalogPricingPreviewUseCase pricingPreview, CatalogPromotionUseCase promotions,
                CatalogVariantUseCase variants, CatalogSkuUseCase skus,
                SkuIdentifierResolutionUseCase skuIdentifiers, GetCatalogItemSnapshotUseCase snapshots) {
            this.accessContext = accessContext;
            this.tenantJdbc = tenantJdbc;
            this.tenantClientAccounts = tenantClientAccounts;
            this.availabilityFactory = availabilityFactory;
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
                    .orElseThrow(() -> new com.nexa.api.shared.application.error.ApiResourceNotFoundException(
                            "client-account"));
            var detailQuery = catalogDetails.bindTo(tenantJdbc, profile, availabilityFactory);
            CatalogItemDetail product = detailQuery.getByCatalogItemId(accessContext.tenantId().value(),
                    accessContext.workspaceId().value(), catalogItemId);
            Optional<CatalogItemSnapshot> quote = snapshots.findActive(catalogItemId,
                    accessContext.tenantId().value(), accessContext.workspaceId().value(), clientAccountId, quantity);
            return new TenantCatalogCommercialPolicyRequestPort.CustomerOffer(profile, product, quote);
        }
    }
}
