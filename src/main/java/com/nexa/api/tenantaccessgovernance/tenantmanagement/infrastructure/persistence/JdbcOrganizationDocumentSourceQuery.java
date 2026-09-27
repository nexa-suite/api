package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Tenant-owned issuer and regional settings for Business Documents. */
@Repository
@Profile("!test")
public class JdbcOrganizationDocumentSourceQuery implements OrganizationDocumentSourceQuery {
    private final JdbcTemplate jdbc;

    public JdbcOrganizationDocumentSourceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Snapshot> find(UUID tenantId, UUID workspaceId) {
        return jdbc.query("select t.name tenant_name,os.legal_name,os.business_identifier,coalesce(rs.currency,'PEN') regional_currency "
                        + "from tenant_management.workspace w join tenant_management.tenant t on t.id=w.tenant_id "
                        + "left join tenant_management.organization_settings os on os.tenant_id=t.id "
                        + "left join tenant_management.regional_settings rs on rs.tenant_id=t.id "
                        + "where w.tenant_id=? and w.id=?",
                (rs, row) -> new Snapshot(rs.getString("tenant_name"), rs.getString("legal_name"),
                        rs.getString("business_identifier"), rs.getString("regional_currency")),
                tenantId, workspaceId).stream().findFirst();
    }
}
