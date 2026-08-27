package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordResetPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetExpiry;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetRequest;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetRequestId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetStatus;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetTokenHash;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPasswordResetPersistenceAdapter implements PasswordResetPersistencePort {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcPasswordResetPersistenceAdapter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override public List<ResetRecord> findPendingByEmailForUpdate(String normalizedEmail) {
        return jdbc.query("select id,normalized_email,surface,status,token_hash,created_at,expires_at,attempts from iam.password_reset_request where normalized_email=? and status='PENDING' for update",
                (rs, row) -> record(rs), normalizedEmail);
    }
    @Override public Optional<ResetRecord> findByTokenHashForUpdate(String tokenHash) {
        return jdbc.query("select id,normalized_email,surface,status,token_hash,created_at,expires_at,attempts from iam.password_reset_request where token_hash=? for update",
                (rs, row) -> record(rs), tokenHash).stream().findFirst();
    }
    @Override public void save(String normalizedEmail, String surface, PasswordResetRequest request) {
        jdbc.update("insert into iam.password_reset_request (id,normalized_email,surface,token_hash,status,attempts,expires_at,created_at) values (?,?,?,?,?,?,?,?)",
                request.id().value(), normalizedEmail, surface, request.tokenHash().value(), request.status().name(), request.attempts(), java.sql.Timestamp.from(request.expiry().value()), java.sql.Timestamp.from(request.createdAt()));
    }
    @Override public void save(ResetRecord record) {
        PasswordResetRequest request = record.aggregate();
        jdbc.update("update iam.password_reset_request set status=?,consumed_at=?,attempts=?,expires_at=? where id=? and status in ('PENDING','EXPIRED')",
                request.status().name(), request.status() == PasswordResetStatus.CONSUMED ? java.sql.Timestamp.from(clock.instant()) : null,
                request.attempts(), java.sql.Timestamp.from(request.expiry().value()), request.id().value());
    }
    private ResetRecord record(java.sql.ResultSet rs) throws java.sql.SQLException {
        PasswordResetRequest aggregate = PasswordResetRequest.restore(new PasswordResetRequestId(rs.getObject("id", UUID.class)), new PasswordResetTokenHash(rs.getString("token_hash")),
                rs.getTimestamp("created_at").toInstant(), new PasswordResetExpiry(rs.getTimestamp("expires_at").toInstant()), PasswordResetStatus.valueOf(rs.getString("status")), rs.getInt("attempts"));
        return new ResetRecord(rs.getObject("id", UUID.class), rs.getString("normalized_email"), rs.getString("surface"), aggregate);
    }
}
