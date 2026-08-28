package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** JDBC projection for inventory_lot.batch_number, scoped before any candidate is returned. */
@Repository
@Profile("!test")
public class JdbcLotIdentifierResolutionQuery implements LotIdentifierResolutionQuery {
    private final JdbcTemplate jdbc;

    public JdbcLotIdentifierResolutionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candidate> resolve(UUID tenantId, UUID workspaceId, String batchNumber) {
        return jdbc.query("select id,sku_id,catalog_item_id,warehouse_id,zone_id,batch_number,expiration_date,received_at,status,unit "
                        + "from warehouse.inventory_lot where tenant_id=? and workspace_id=? and batch_number=? order by id",
                (rs, row) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("zone_id", UUID.class), rs.getString("batch_number"),
                        rs.getObject("expiration_date", java.time.LocalDate.class),
                        rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
                        rs.getString("status"), rs.getString("unit")),
                tenantId, workspaceId, batchNumber);
    }
}
