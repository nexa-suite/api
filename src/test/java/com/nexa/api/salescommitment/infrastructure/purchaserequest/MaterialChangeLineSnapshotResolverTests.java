package com.nexa.api.salescommitment.infrastructure.purchaserequest;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestLineView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialChangeLineSnapshotResolverTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SKU = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();
    private static final BigDecimal QUANTITY = new BigDecimal("2.00");

    @Test
    void resolvesActiveSkuForExactTenantWorkspaceAccountAndQuantity() {
        var reference = skuReference(new BigDecimal("12.50"));
        FakeSellableSkuQuery query = new FakeSellableSkuQuery(Optional.of(reference));

        var resolved = new MaterialChangeLineSnapshotResolver(query).resolve(TENANT.toString(),
                WORKSPACE.toString(), ACCOUNT.toString(), List.of(line(new BigDecimal("12.50"))));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().sku()).isSameAs(reference);
        assertThat(query.tenantId).isEqualTo(TENANT);
        assertThat(query.workspaceId).isEqualTo(WORKSPACE);
        assertThat(query.customerAccountId).isEqualTo(ACCOUNT);
        assertThat(query.legacyCatalogItemId).isEqualTo("CAT-0002");
        assertThat(query.quantity).isEqualTo(QUANTITY);
    }

    @Test
    void rejectsMissingCurrentSkuAndPriceMismatchBeforePersistence() {
        FakeSellableSkuQuery query = new FakeSellableSkuQuery(Optional.empty());
        var resolver = new MaterialChangeLineSnapshotResolver(query);

        assertThatThrownBy(() -> resolver.resolve(TENANT.toString(), WORKSPACE.toString(), ACCOUNT.toString(),
                List.of(line(new BigDecimal("12.50")))))
                .isInstanceOfSatisfying(CommercialBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("COMMERCIAL_POLICY_CHANGED"));

        query.result = Optional.of(skuReference(new BigDecimal("13.00")));
        assertThatThrownBy(() -> resolver.resolve(TENANT.toString(), WORKSPACE.toString(), ACCOUNT.toString(),
                List.of(line(new BigDecimal("12.50")))))
                .isInstanceOfSatisfying(CommercialBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("COMMERCIAL_POLICY_CHANGED"));
    }

    private static PurchaseRequestLineView line(BigDecimal price) {
        return new PurchaseRequestLineView("line-1", "CAT-0002", "Product", "Pack", QUANTITY,
                "UNIT", price, "PEN", null, 0);
    }

    private static SellableSkuQuery.SellableSkuReference skuReference(BigDecimal price) {
        return new SellableSkuQuery.SellableSkuReference(SKU, FAMILY, "FAM-1", "SKU-1", "CAT-0002",
                "Product", "Pack", "UNIT", price, "PEN");
    }

    private static final class FakeSellableSkuQuery implements SellableSkuQuery {
        private Optional<SellableSkuReference> result;
        private UUID tenantId;
        private UUID workspaceId;
        private UUID customerAccountId;
        private String legacyCatalogItemId;
        private BigDecimal quantity;

        private FakeSellableSkuQuery(Optional<SellableSkuReference> result) {
            this.result = result;
        }

        @Override
        public Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID skuId) {
            throw new AssertionError("Unexpected SKU lookup");
        }

        @Override
        public Optional<SellableSkuPolicy> findPhysicalValidationPolicy(UUID tenantId, UUID workspaceId, UUID skuId) {
            throw new AssertionError("Unexpected physical policy lookup");
        }

        @Override
        public Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(
                UUID tenantId, UUID workspaceId, String legacyCatalogItemId) {
            throw new AssertionError("Expected quantity-aware SKU lookup");
        }

        @Override
        public Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(UUID tenantId, UUID workspaceId,
                UUID customerAccountId, String legacyCatalogItemId, BigDecimal quantity) {
            this.tenantId = tenantId;
            this.workspaceId = workspaceId;
            this.customerAccountId = customerAccountId;
            this.legacyCatalogItemId = legacyCatalogItemId;
            this.quantity = quantity;
            return result;
        }
    }
}
