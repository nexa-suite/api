package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.iam.application.port.in.ListOwnSessionsQuery;
import com.nexa.api.iam.application.port.in.RevokeOtherSessionsCommand;
import com.nexa.api.iam.application.port.in.RevokeOwnSessionCommand;
import com.nexa.api.iam.application.port.out.IamSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class SessionSecurityService implements ListOwnSessionsQuery, RevokeOwnSessionCommand, RevokeOtherSessionsCommand {
    private final IamSecurityRepository repository;

    public SessionSecurityService(IamSecurityRepository repository) { this.repository = repository; }

    @Override
    @Transactional(readOnly = true)
    public List<Session> list(Actor actor) { return repository.sessions(actor); }

    @Override
    @Transactional
    public void revoke(Actor actor, UUID sessionId) { repository.revokeSession(actor, sessionId); }

    @Override
    @Transactional
    public void revokeOthers(Actor actor) { repository.revokeOtherSessions(actor); }
}
