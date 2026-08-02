package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.iam.application.port.out.RefreshSessionPersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcRefreshSessionPersistenceAdapter implements RefreshSessionPersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcRefreshSessionPersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public List<Session> findOwnSessions(Actor actor) { return delegate.findOwnSessions(actor); }
    public void revoke(Actor actor, UUID sessionId) { delegate.revoke(actor, sessionId); }
    public void revokeAllExceptCurrent(Actor actor) { delegate.revokeAllExceptCurrent(actor); }
}
