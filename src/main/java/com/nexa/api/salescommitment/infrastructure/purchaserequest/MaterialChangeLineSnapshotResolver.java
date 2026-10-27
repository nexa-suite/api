package com.nexa.api.salescommitment.infrastructure.purchaserequest;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestLineView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resolves internal SKU snapshots for accepted, already price-revalidated material-change lines. */
final class MaterialChangeLineSnapshotResolver {
    private final SellableSkuQuery sellableSkus;

    MaterialChangeLineSnapshotResolver(SellableSkuQuery sellableSkus) {
        this.sellableSkus = Objects.requireNonNull(sellableSkus, "Sellable SKU query is required");
    }

    List<ResolvedLine> resolve(String tenantId, String workspaceId, String buyerAccountId,
            List<PurchaseRequestLineView> lines) {
        UUID tenant = UUID.fromString(tenantId);
        UUID workspace = UUID.fromString(workspaceId);
        UUID buyerAccount = UUID.fromString(buyerAccountId);
        List<ResolvedLine> resolved = new ArrayList<>(lines.size());
        for (PurchaseRequestLineView line : lines) {
            var sku = sellableSkus.findActiveByLegacyCatalogItemId(tenant, workspace, buyerAccount,
                            line.catalogItemId(), line.quantity())
                    .filter(value -> matches(line, value))
                    .orElseThrow(() -> new CommercialBusinessException("COMMERCIAL_POLICY_CHANGED"));
            resolved.add(new ResolvedLine(line, sku));
        }
        return List.copyOf(resolved);
    }

    private static boolean matches(PurchaseRequestLineView line, SellableSkuQuery.SellableSkuReference sku) {
        BigDecimal price = line.unitPriceAmount();
        return sku.skuId() != null && sku.familyId() != null && nonBlank(sku.skuCode())
                && nonBlank(sku.familyCode()) && Objects.equals(line.catalogItemId(), sku.legacyCatalogItemId())
                && price != null && sku.price() != null && price.compareTo(sku.price()) == 0
                && line.unitPriceCurrency() != null && sku.currency() != null
                && line.unitPriceCurrency().equalsIgnoreCase(sku.currency());
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    record ResolvedLine(PurchaseRequestLineView line, SellableSkuQuery.SellableSkuReference sku) { }
}
