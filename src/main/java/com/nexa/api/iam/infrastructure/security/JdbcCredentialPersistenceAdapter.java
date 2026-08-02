package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.CredentialPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcCredentialPersistenceAdapter implements CredentialPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcCredentialPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<CredentialRecord> findByUserId(UUID userId) {
        return jdbc.query("select u.id,u.email,c.password_hash from iam.user_account u join iam.password_credential c on c.user_id=u.id where u.id=?",
                (rs, row) -> new CredentialRecord(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), userId).stream().findFirst();
    }

    @Override public Optional<CredentialRecord> findActiveByNormalizedEmail(String normalizedEmail) {
        return jdbc.query("select u.id,u.email,c.password_hash from iam.user_account u join iam.password_credential c on c.user_id=u.id where u.normalized_email=? and u.status='ACTIVE' for update",
                (rs, row) -> new CredentialRecord(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), normalizedEmail).stream().findFirst();
    }

    @Override public void updateCredentialHash(UUID userId, String passwordHash, Instant changedAt) {
        if (jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=? where user_id=?", passwordHash, java.sql.Timestamp.from(changedAt), userId) != 1)
            throw new IllegalStateException("CREDENTIAL_NOT_FOUND");
    }
}
