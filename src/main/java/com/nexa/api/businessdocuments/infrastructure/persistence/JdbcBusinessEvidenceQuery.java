package com.nexa.api.businessdocuments.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Read-only BC-09 evidence availability projection. */
@Repository
@Profile("!test")
public class JdbcBusinessEvidenceQuery implements BusinessEvidenceQuery {
    private final JdbcTemplate jdbc;

    public JdbcBusinessEvidenceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAvailable(UUID tenantId, UUID workspaceId, UUID evidenceObjectId) {
        Boolean available = jdbc.query("select exists(select 1 from business_documents.evidence_object where tenant_id=? and workspace_id=? and id=? and lifecycle_status='AVAILABLE')",
                (rs, row) -> rs.getBoolean(1), tenantId, workspaceId, evidenceObjectId)
                .stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(available);
    }
}
