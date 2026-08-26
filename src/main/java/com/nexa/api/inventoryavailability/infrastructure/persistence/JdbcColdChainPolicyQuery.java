package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Reads the zone policy attached to the lots in a physical allocation. */
@Repository
@Profile("!test")
public class JdbcColdChainPolicyQuery implements ColdChainPolicyQuery {
    private final JdbcTemplate jdbc;

    public JdbcColdChainPolicyQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Range> rangeForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select z.temperature_min,z.temperature_max,'CELSIUS' from warehouse.physical_allocation a join warehouse.physical_allocation_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.physical_allocation_id=a.id join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id join logistics.fulfillment f on f.tenant_id=a.tenant_id and f.workspace_id=a.workspace_id and f.physical_allocation_id=a.id join logistics.delivery d on d.tenant_id=f.tenant_id and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id where a.tenant_id=? and a.workspace_id=? and d.id=? and z.temperature_min is not null and z.temperature_max is not null order by l.id limit 1",
                        (rs, row) -> new Range(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)),
                        tenantId, workspaceId, deliveryId).stream().findFirst();
    }
}
