package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerCatalogFavoriteUseCase;
import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerCatalogFavoritePersistencePort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoriteView;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoritePage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerCatalogFavoritePersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Routes BC-02 Buyer favorites and resolves each saved SKU through current BC-03 offer rules.
 * Stored favorites remain in pages when the current offer is hidden or inactive; those entries have no current offer.
 */
public final class TenantBoundBuyerCatalogFavoriteUseCase implements BuyerCatalogFavoriteUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantBuyerCatalogFavoritePersistenceFactory favorites;
    private final TenantCatalogDetailQueryFactory catalogDetails;
    private final TenantCatalogAvailabilityAdapterFactory catalogAvailability;

    public TenantBoundBuyerCatalogFavoriteUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantBuyerCatalogFavoritePersistenceFactory favorites,
            TenantCatalogDetailQueryFactory catalogDetails,
            TenantCatalogAvailabilityAdapterFactory catalogAvailability) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant Customer Account query factory is required");
        this.favorites = Objects.requireNonNull(favorites, "Tenant favorite persistence factory is required");
        this.catalogDetails = Objects.requireNonNull(catalogDetails, "Tenant Catalog query factory is required");
        this.catalogAvailability = Objects.requireNonNull(catalogAvailability,
                "Tenant Catalog Availability adapter factory is required");
    }

    @Override
    public BuyerCatalogFavoritePage list(CurrentAccessContext context, int page, int size) {
        authorizeBuyer(context);
        context.requirePermission(Permission.SALES_BUYER_READ);
        context.requirePermission(Permission.CATALOG_READ);
        catalogDetails.requireCatalogRead();
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Favorite page and size are outside the supported range");
        }
        return router.inTransaction(context, jdbc -> {
            BuyerScope buyer = resolveActiveBuyer(context, jdbc);
            setMembershipScope(context, jdbc);
            BuyerCatalogFavoritePersistencePort persistence = favorites.bindTo(jdbc);
            TenantCatalogDetailQueryFactory.TenantCatalogReadQuery offers = catalogQuery(jdbc, buyer.profile());
            UUID tenantId = context.tenantId().value();
            UUID workspaceId = context.workspaceId().value();
            UUID membershipId = context.membershipId().value();
            long totalItems = persistence.count(tenantId, workspaceId, membershipId);
            List<BuyerCatalogFavoritePersistencePort.StoredFavorite> saved = persistence.findPage(tenantId,
                    workspaceId, membershipId, size, (long) page * size);
            Map<UUID, CatalogItemDetail> currentOffers = offers.findBySellableSkuIds(tenantId, workspaceId,
                            saved.stream().map(BuyerCatalogFavoritePersistencePort.StoredFavorite::sellableSkuId).toList()).stream()
                    .collect(Collectors.toMap(offer -> UUID.fromString(offer.sellableSkuId()), offer -> offer));
            List<BuyerCatalogFavoriteView> items = saved.stream()
                    .map(favorite -> view(favorite.sellableSkuId(), favorite.createdAt(),
                            currentOffers.get(favorite.sellableSkuId())))
                    .toList();
            return new BuyerCatalogFavoritePage(items, page, size, totalItems);
        });
    }

    @Override
    public boolean isSaved(CurrentAccessContext context, UUID sellableSkuId) {
        authorizeBuyer(context);
        context.requirePermission(Permission.SALES_BUYER_READ);
        context.requirePermission(Permission.CATALOG_READ);
        UUID skuId = Objects.requireNonNull(sellableSkuId, "Sellable SKU id is required");
        return router.inTransaction(context, jdbc -> {
            resolveActiveBuyer(context, jdbc);
            setMembershipScope(context, jdbc);
            return favorites.bindTo(jdbc).contains(context.tenantId().value(), context.workspaceId().value(),
                    context.membershipId().value(), skuId);
        });
    }

    @Override
    public Optional<BuyerCatalogFavoriteView> add(CurrentAccessContext context, UUID sellableSkuId) {
        authorizeBuyer(context);
        context.requirePermission(Permission.SALES_BUYER_WRITE);
        context.requirePermission(Permission.CATALOG_READ);
        catalogDetails.requireCatalogRead();
        UUID skuId = Objects.requireNonNull(sellableSkuId, "Sellable SKU id is required");
        return router.inTransaction(context, jdbc -> {
            BuyerScope buyer = resolveActiveBuyer(context, jdbc);
            setMembershipScope(context, jdbc);
            UUID tenantId = context.tenantId().value();
            UUID workspaceId = context.workspaceId().value();
            Optional<CatalogItemDetail> current = catalogQuery(jdbc, buyer.profile())
                    .findBySellableSkuId(tenantId, workspaceId, skuId);
            if (current.isEmpty()) return Optional.empty();

            Instant createdAt = favorites.bindTo(jdbc).addIfAbsent(tenantId, workspaceId,
                    context.membershipId().value(), skuId);
            return Optional.of(view(skuId, createdAt, current.orElseThrow()));
        });
    }

    @Override
    public void remove(CurrentAccessContext context, UUID sellableSkuId) {
        authorizeBuyer(context);
        context.requirePermission(Permission.SALES_BUYER_WRITE);
        UUID skuId = Objects.requireNonNull(sellableSkuId, "Sellable SKU id is required");
        router.inTransaction(context, jdbc -> {
            resolveActiveBuyer(context, jdbc);
            setMembershipScope(context, jdbc);
            favorites.bindTo(jdbc).remove(context.tenantId().value(), context.workspaceId().value(),
                    context.membershipId().value(), skuId);
            return null;
        });
    }

    private static void authorizeBuyer(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer membership is required to manage catalog favorites");
        }
    }

    private BuyerScope resolveActiveBuyer(CurrentAccessContext context, JdbcTemplate jdbc) {
        CustomerAccountQuery query = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account query factory returned no query");
        CustomerAccountDetails account = query.findActiveBuyerDetails(context.tenantId().toString(),
                        context.workspaceId().toString(), context.membershipId().toString())
                .filter(CustomerAccountDetails::active)
                .orElseThrow(() -> new AccessPolicyViolation("Active Buyer relationship is required"));
        CatalogClientAccountPort.ClientAccountProfile profile = new CatalogClientAccountPort.ClientAccountProfile(
                UUID.fromString(account.id()), account.segment(), null);
        return new BuyerScope(profile);
    }

    private static void setMembershipScope(CurrentAccessContext context, JdbcTemplate jdbc) {
        String installed = jdbc.queryForObject("select set_config('app.current_membership_id', ?, true)",
                String.class, context.membershipId().toString());
        if (!context.membershipId().toString().equals(installed)) {
            throw new AccessPolicyViolation("Verified Buyer membership scope could not be applied");
        }
    }

    private TenantCatalogDetailQueryFactory.TenantCatalogReadQuery catalogQuery(JdbcTemplate jdbc,
            CatalogClientAccountPort.ClientAccountProfile buyer) {
        return catalogDetails.bindTo(jdbc, buyer, catalogAvailability::bindTo);
    }

    private static BuyerCatalogFavoriteView view(UUID sellableSkuId, Instant favoritedAt,
            CatalogItemDetail currentOffer) {
        return new BuyerCatalogFavoriteView(sellableSkuId, favoritedAt, currentOffer);
    }

    private record BuyerScope(CatalogClientAccountPort.ClientAccountProfile profile) { }
}
