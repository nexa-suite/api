package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.iam.application.port.in.ListOwnSessionsQuery;
import com.nexa.api.iam.application.port.in.RevokeOtherSessionsCommand;
import com.nexa.api.iam.application.port.in.RevokeOwnSessionCommand;
import com.nexa.api.iam.application.port.out.RefreshSessionPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class SessionSecurityService implements ListOwnSessionsQuery, RevokeOwnSessionCommand, RevokeOtherSessionsCommand {
    private final RefreshSessionPersistencePort repository;

    public SessionSecurityService(RefreshSessionPersistencePort repository) { this.repository = repository; }

    @Override
    @Transactional(readOnly = true)
    public List<Session> list(Actor actor) { return repository.findOwnSessions(actor); }

    @Override
    @Transactional
    public void revoke(Actor actor, UUID sessionId) { repository.revoke(actor, sessionId); }

    @Override
    @Transactional
    public void revokeOthers(Actor actor) { repository.revokeAllExceptCurrent(actor); }
}
