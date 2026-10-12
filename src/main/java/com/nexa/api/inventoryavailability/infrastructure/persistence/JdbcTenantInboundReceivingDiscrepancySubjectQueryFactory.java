package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInboundReceivingDiscrepancySubjectQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's subject projection with Tenant JDBC and the actor grant snapshot from preflight. */
@Component
@Profile("!test")
public final class JdbcTenantInboundReceivingDiscrepancySubjectQueryFactory
        implements TenantInboundReceivingDiscrepancySubjectQueryFactory {
    @Override
    public InboundReceivingDiscrepancySubjectQuery bindTo(JdbcTemplate tenantJdbc,
                                                           WarehouseObjectAccess verifiedWarehouseAccess) {
        return new WarehouseInboundReceivingDiscrepancySubjectQuery(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(verifiedWarehouseAccess, "Preflighted Warehouse access is required"));
    }
}
