package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Narrow tenant-scoped BC-05 projection consumed by BC-09 subject validation. */
@Repository
@Profile("!test")
public class WarehouseInboundReceivingDiscrepancySubjectQuery implements InboundReceivingDiscrepancySubjectQuery {
    private final JdbcTemplate jdbc;
    private final WarehouseObjectAccess warehouseAccess;

    public WarehouseInboundReceivingDiscrepancySubjectQuery(JdbcTemplate jdbc, WarehouseObjectAccess warehouseAccess) {
        this.jdbc = jdbc;
        this.warehouseAccess = warehouseAccess;
    }

    @Override
    public Optional<Subject> find(UUID tenantId, UUID workspaceId, UUID membershipId, UUID caseId) {
        return jdbc.query("select id,warehouse_id,status from warehouse.inbound_receiving_discrepancy_case "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new WarehouseSubject(rs.getObject("id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getString("status")),
                tenantId, workspaceId, caseId).stream()
                .filter(value -> warehouseAccess.hasActiveGrant(tenantId, workspaceId, membershipId, value.warehouseId()))
                .map(value -> new Subject(value.id(), value.status()))
                .findFirst();
    }

    private record WarehouseSubject(UUID id, UUID warehouseId, String status) { }
}
