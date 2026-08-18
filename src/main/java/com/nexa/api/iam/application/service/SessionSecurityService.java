package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.iam.application.port.in.ListOwnSessionsQuery;
import com.nexa.api.iam.application.port.in.RevokeOtherSessionsCommand;
import com.nexa.api.iam.application.port.in.RevokeOwnSessionCommand;
import com.nexa.api.iam.application.port.out.RefreshSessionPersistencePort;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class SessionSecurityService implements ListOwnSessionsQuery, RevokeOwnSessionCommand, RevokeOtherSessionsCommand {
    private final RefreshSessionPersistencePort sessions;
    private final SecurityAuditPort audit;
    private final Clock clock;

    public SessionSecurityService(RefreshSessionPersistencePort sessions, SecurityAuditPort audit, Clock clock) {
        this.sessions = sessions; this.audit = audit; this.clock = clock;
    }

    @Override @Transactional(readOnly = true)
    public List<Session> list(Actor actor) { return sessions.findOwnSessions(actor); }

    @Override @Transactional
    public void revoke(Actor actor, UUID sessionId) {
        sessions.revoke(actor, sessionId);
        audit.append(event("SESSION_REVOKED", actor, Map.of("sessionId", sessionId.toString())));
    }

    @Override @Transactional
    public void revokeOthers(Actor actor) {
        int count = sessions.revokeAllExceptCurrent(actor);
        audit.append(event("ALL_OTHER_SESSIONS_REVOKED", actor, Map.of("count", count)));
    }

    private SecurityAuditPort.Event event(String type, Actor actor, Map<String, Object> metadata) {
        return new SecurityAuditPort.Event(type, actor.userId(), actor.userId(), actor.tenantId(), actor.workspaceId(), actor.surface(),
                value(actor.correlationId()), value(actor.traceId()), clock.instant(), metadata);
    }
    private static String value(String text) { return text == null || text.isBlank() ? "unknown" : text; }
}
