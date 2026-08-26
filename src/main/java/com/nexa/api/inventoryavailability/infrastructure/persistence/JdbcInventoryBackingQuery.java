package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC projection for the inventory-owned commercial backing. */
@Repository
@Profile("!test")
public final class JdbcInventoryBackingQuery implements InventoryBackingQuery {
    private final JdbcTemplate jdbc;

    public JdbcInventoryBackingQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Snapshot> findByCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId) {
        return jdbc.query("select id,commercial_commitment_id,status from warehouse.inventory_backing where tenant_id=? and workspace_id=? and commercial_commitment_id=?",
                        (rs, row) -> new Header(rs.getObject("id", UUID.class),
                                rs.getObject("commercial_commitment_id", UUID.class), rs.getString("status")),
                        tenantId, workspaceId, commercialCommitmentId)
                .stream().findFirst()
                .map(header -> new Snapshot(header.id(), header.commitmentId(), header.status(), positions(tenantId, workspaceId, header.id())));
    }

    private List<Position> positions(UUID tenantId, UUID workspaceId, UUID backingId) {
        return jdbc.query("select bl.sku_id,bl.catalog_item_id,bl.unit,p.warehouse_id,p.quantity from warehouse.inventory_backing_position p join warehouse.inventory_backing_line bl on bl.tenant_id=p.tenant_id and bl.workspace_id=p.workspace_id and bl.id=p.backing_line_id where p.tenant_id=? and p.workspace_id=? and bl.backing_id=? order by bl.sku_id,p.warehouse_id,p.id",
                (rs, row) -> new Position(rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"),
                        rs.getString("unit"), rs.getObject("warehouse_id", UUID.class), rs.getBigDecimal("quantity")),
                tenantId, workspaceId, backingId);
    }

    private record Header(UUID id, UUID commitmentId, String status) { }
}
