package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels.Evidence;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.LegalAcceptanceStore;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcLegalAcceptanceStore implements LegalAcceptanceStore {
    private final JdbcTemplate jdbc;
    public JdbcLegalAcceptanceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Evidence> find(UUID userId, String documentId, String version) {
        return jdbc.query("select content_digest, accepted_at from iam.legal_acceptance where user_id=? and document_id=? and document_version=?",
                (rs, row) -> new Evidence(rs.getString(1), rs.getTimestamp(2).toInstant()),
                userId, documentId, version).stream().findFirst();
    }

    @Override
    public void insertIfAbsent(UUID userId, String documentId, String version, String digest) {
        jdbc.update("insert into iam.legal_acceptance (user_id, document_id, document_version, content_digest) values (?, ?, ?, ?) on conflict (user_id, document_id, document_version) do nothing",
                userId, documentId, version, digest);
    }
}
