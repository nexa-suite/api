package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 commercial inventory backing to one Tenant transaction session. */
@FunctionalInterface
public interface TenantInventoryBackingCommandsFactory {
    InventoryBackingCommands bindTo(JdbcTemplate tenantJdbc, SellableSkuQuery tenantSellableSkus);
}
