package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInboundReceivingDiscrepancyCasesFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses inbound discrepancy rules on the routed Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantInboundReceivingDiscrepancyCasesFactory
        implements TenantInboundReceivingDiscrepancyCasesFactory {
    @Override
    public InboundReceivingDiscrepancyCases bindTo(JdbcTemplate tenantJdbc,
                                                    WarehouseObjectAccess verifiedWarehouseAccess,
                                                    SellableSkuQuery tenantSellableSkus) {
        return new WarehouseInboundReceivingDiscrepancyAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(verifiedWarehouseAccess, "Verified Warehouse access is required"),
                Objects.requireNonNull(tenantSellableSkus, "Tenant SKU query is required"));
    }
}
