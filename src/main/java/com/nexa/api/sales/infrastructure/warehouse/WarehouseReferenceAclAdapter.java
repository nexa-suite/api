package com.nexa.api.sales.infrastructure.warehouse;

import com.nexa.api.sales.application.port.out.WarehouseReferencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** ACL adapter for the warehouse origin used by Sales snapshots. */
@Repository
@Profile("!test")
public class WarehouseReferenceAclAdapter implements WarehouseReferencePort {
    private final JdbcTemplate jdbc;

    public WarehouseReferenceAclAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<WarehouseReference> findActive(String tenantId, String workspaceId, String warehouseId) {
        return jdbc.query("select w.id,w.code,w.name,coalesce(nullif(w.address,''),'Warehouse address not configured'),"
                        + "coalesce(c.priority,0),coalesce(c.preferred,false),coalesce(c.service_status,'OPERATIONAL'),"
                        + "c.latitude,c.longitude "
                        + "from warehouse.warehouse w left join warehouse.warehouse_service_configuration c "
                        + "on c.tenant_id=w.tenant_id and c.workspace_id=w.workspace_id and c.warehouse_id=w.id "
                        + "where w.tenant_id=? and w.workspace_id=? and w.id=? and w.status='ACTIVE' "
                        + "and coalesce(c.service_status,'OPERATIONAL')='OPERATIONAL'",
                rs -> rs.next() ? Optional.of(reference(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId), uuid(warehouseId));
    }

    @Override
    public Optional<WarehouseReference> findPrimary(String tenantId, String workspaceId) {
        return jdbc.query("select w.id,w.code,w.name,coalesce(nullif(w.address,''),'Warehouse address not configured'),"
                        + "coalesce(c.priority,0),coalesce(c.preferred,false),coalesce(c.service_status,'OPERATIONAL'),"
                        + "c.latitude,c.longitude "
                        + "from warehouse.warehouse w left join warehouse.warehouse_service_configuration c "
                        + "on c.tenant_id=w.tenant_id and c.workspace_id=w.workspace_id and c.warehouse_id=w.id "
                        + "where w.tenant_id=? and w.workspace_id=? and w.status='ACTIVE' "
                        + "and coalesce(c.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "order by coalesce(c.preferred,false) desc,coalesce(c.priority,0) desc,w.code,w.id limit 1",
                rs -> rs.next() ? Optional.of(reference(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId));
    }

    private static WarehouseReference reference(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean preferred = rs.getBoolean(6);
        int priority = rs.getInt(5);
        String status = rs.getString(7);
        String reason = preferred ? "PREFERRED_OPERATIONAL" : priority > 0 ? "PRIORITIZED_OPERATIONAL" : "ACTIVE_OPERATIONAL_FALLBACK";
        return new WarehouseReference(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4),
                reason, status, priority, preferred, java.time.Instant.now(), rs.getBigDecimal(8), rs.getBigDecimal(9));
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
