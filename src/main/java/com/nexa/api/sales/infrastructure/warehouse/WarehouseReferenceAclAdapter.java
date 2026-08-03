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
        return jdbc.query("select id,code,name,coalesce(nullif(address,''),'Warehouse address not configured') "
                        + "from warehouse.warehouse where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                rs -> rs.next() ? Optional.of(reference(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId), uuid(warehouseId));
    }

    @Override
    public Optional<WarehouseReference> findPrimary(String tenantId, String workspaceId) {
        return jdbc.query("select id,code,name,coalesce(nullif(address,''),'Warehouse address not configured') "
                        + "from warehouse.warehouse where tenant_id=? and workspace_id=? and status='ACTIVE' "
                        + "order by code,id limit 1",
                rs -> rs.next() ? Optional.of(reference(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId));
    }

    private static WarehouseReference reference(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WarehouseReference(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4));
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
