package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.RefreshSessionPersistencePort;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcRefreshSessionPersistenceAdapter implements RefreshSessionPersistencePort {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcRefreshSessionPersistenceAdapter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override public List<Session> findOwnSessions(Actor actor) {
        return jdbc.query("select id,surface,created_at,coalesce(last_seen_at,last_used_at,created_at),expires_at,device_label,coarse_ip from iam.refresh_session where user_id=? and revoked_at is null and expires_at>now() order by created_at desc limit 50",
                (rs, row) -> new Session(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getObject(1, UUID.class).equals(actor.sessionId()), rs.getString(6), rs.getString(7)), actor.userId());
    }

    @Override public void revoke(Actor actor, UUID sessionId) {
        int changed = jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where id=? and user_id=? and revoked_at is null", now(), now(), sessionId, actor.userId());
        if (changed != 1) throw new ApiResourceNotFoundException("session");
    }

    @Override public int revokeAllExceptCurrent(Actor actor) { return revokeAllForUser(actor.userId(), actor.sessionId()); }

    @Override public int revokeAllForUser(UUID userId, UUID exceptSessionId) {
        return jdbc.update("update iam.refresh_session set revoked_at=?,family_revoked_at=? where user_id=? and (cast(? as uuid) is null or id<>cast(? as uuid)) and revoked_at is null", now(), now(), userId, exceptSessionId, exceptSessionId);
    }

    private java.sql.Timestamp now() { return java.sql.Timestamp.from(clock.instant()); }
}
