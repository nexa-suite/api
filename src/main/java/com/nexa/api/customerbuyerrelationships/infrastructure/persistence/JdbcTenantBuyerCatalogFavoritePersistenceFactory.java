package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerCatalogFavoritePersistencePort;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerCatalogFavoritePersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates BC-02 favorite persistence bound to the caller's verified Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantBuyerCatalogFavoritePersistenceFactory
        implements TenantBuyerCatalogFavoritePersistenceFactory {
    @Override
    public BuyerCatalogFavoritePersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcTenantBuyerCatalogFavoritePersistence(Objects.requireNonNull(tenantJdbc,
                "Routed Tenant JDBC session is required"));
    }

    private record JdbcTenantBuyerCatalogFavoritePersistence(JdbcTemplate jdbc)
            implements BuyerCatalogFavoritePersistencePort {
        @Override
        public List<StoredFavorite> findPage(UUID tenantId, UUID workspaceId, UUID buyerMembershipId,
                int limit, long offset) {
            return jdbc.query("select sellable_sku_id, created_at from sales.buyer_catalog_favorite "
                            + "where tenant_id=? and workspace_id=? and buyer_membership_id=? "
                            + "order by created_at desc, sellable_sku_id limit ? offset ?",
                    (rs, row) -> new StoredFavorite(rs.getObject("sellable_sku_id", UUID.class),
                            rs.getTimestamp("created_at").toInstant()),
                    tenantId, workspaceId, buyerMembershipId, limit, offset);
        }

        @Override
        public long count(UUID tenantId, UUID workspaceId, UUID buyerMembershipId) {
            Long total = jdbc.queryForObject("select count(*) from sales.buyer_catalog_favorite "
                            + "where tenant_id=? and workspace_id=? and buyer_membership_id=?",
                    Long.class, tenantId, workspaceId, buyerMembershipId);
            return total == null ? 0 : total;
        }

        @Override
        public Instant addIfAbsent(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId) {
            lockFavoriteKey(tenantId, workspaceId, buyerMembershipId, sellableSkuId);
            jdbc.update("insert into sales.buyer_catalog_favorite "
                            + "(tenant_id, workspace_id, buyer_membership_id, sellable_sku_id) values (?,?,?,?) "
                            + "on conflict (tenant_id, workspace_id, buyer_membership_id, sellable_sku_id) do nothing",
                    tenantId, workspaceId, buyerMembershipId, sellableSkuId);
            Timestamp createdAt = jdbc.queryForObject("select created_at from sales.buyer_catalog_favorite "
                            + "where tenant_id=? and workspace_id=? and buyer_membership_id=? and sellable_sku_id=?",
                    Timestamp.class, tenantId, workspaceId, buyerMembershipId, sellableSkuId);
            return Objects.requireNonNull(createdAt, "Inserted Buyer favorite timestamp is required").toInstant();
        }

        @Override
        public void remove(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId) {
            lockFavoriteKey(tenantId, workspaceId, buyerMembershipId, sellableSkuId);
            jdbc.update("delete from sales.buyer_catalog_favorite where tenant_id=? and workspace_id=? "
                            + "and buyer_membership_id=? and sellable_sku_id=?",
                    tenantId, workspaceId, buyerMembershipId, sellableSkuId);
        }

        private void lockFavoriteKey(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId) {
            String key = tenantId + ":" + workspaceId + ":buyer-catalog-favorite:" + buyerMembershipId + ":"
                    + sellableSkuId;
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 1, key);
        }
    }
}
