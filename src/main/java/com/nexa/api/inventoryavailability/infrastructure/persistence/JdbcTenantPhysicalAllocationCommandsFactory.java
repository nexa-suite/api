package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's physical allocation rules on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantPhysicalAllocationCommandsFactory implements TenantPhysicalAllocationCommandsFactory {
    @Override
    public PhysicalAllocationCommands bindTo(JdbcTemplate tenantJdbc,
                                             BusinessTraceabilityCommands tenantTraceability,
                                             InventoryCommercialSource tenantCommercialSource,
                                             InventoryFulfillmentSource tenantFulfillmentSource,
                                             SellableSkuQuery tenantSellableSkus,
                                             CanonicalOutboxPort tenantCanonicalOutbox,
                                             WarehouseObjectAccess verifiedWarehouseAccess) {
        return new WarehousePhysicalAllocationAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantTraceability, "Tenant traceability commands are required"),
                Objects.requireNonNull(tenantCommercialSource, "Tenant Sales source is required"),
                Objects.requireNonNull(tenantFulfillmentSource, "Tenant fulfillment source is required"),
                Objects.requireNonNull(tenantSellableSkus, "Tenant Sellable SKU query is required"),
                Objects.requireNonNull(tenantCanonicalOutbox, "Tenant canonical outbox is required"),
                Objects.requireNonNull(verifiedWarehouseAccess, "Preflight Warehouse access is required"));
    }
}
