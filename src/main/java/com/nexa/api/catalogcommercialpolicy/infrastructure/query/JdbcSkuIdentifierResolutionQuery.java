package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SkuIdentifierResolutionQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** JDBC projection for the existing sellable_sku.sku_code and sellable_sku.gtin fields. */
@Repository
@Profile("!test")
public class JdbcSkuIdentifierResolutionQuery implements SkuIdentifierResolutionQuery {
    private final JdbcTemplate jdbc;

    public JdbcSkuIdentifierResolutionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candidate> resolve(UUID tenantId, UUID workspaceId, String identifier) {
        return jdbc.query("select id,sku_code,gtin,presentation,unit_of_measure,status,visible "
                        + "from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and status='ACTIVE' and visible "
                        + "and (sku_code=? or gtin=?) order by id",
                (rs, row) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("sku_code"),
                        rs.getString("gtin"), rs.getString("presentation"), rs.getString("unit_of_measure"),
                        rs.getString("status"), rs.getBoolean("visible")),
                tenantId, workspaceId, identifier, identifier);
    }
}
