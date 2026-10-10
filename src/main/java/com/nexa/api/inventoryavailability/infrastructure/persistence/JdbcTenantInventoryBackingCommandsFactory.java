package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryBackingCommandsFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates BC-05's existing backing adapter on the caller's exact Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantInventoryBackingCommandsFactory implements TenantInventoryBackingCommandsFactory {
    @Override
    public InventoryBackingCommands bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus) {
        return new WarehouseCommercialBackingAdapter(Objects.requireNonNull(tenantJdbc,
                        "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantSellableSkus, "Tenant Sellable SKU query is required"));
    }
}
