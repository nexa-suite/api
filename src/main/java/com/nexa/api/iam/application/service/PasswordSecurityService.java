package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.port.in.ChangeOwnPasswordCommand;
import com.nexa.api.iam.application.port.out.CredentialPersistencePort;
import com.nexa.api.iam.application.port.out.PasswordHashPort;
import com.nexa.api.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.iam.application.port.out.RefreshSessionPersistencePort;
import com.nexa.api.iam.application.port.out.SecurityNotificationOutboxPort;
import com.nexa.api.iam.domain.model.password.PasswordPolicy;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordSecurityService implements ChangeOwnPasswordCommand {
    private final CredentialPersistencePort credentials;
    private final PasswordVerificationPort verifier;
    private final PasswordHashPort hasher;
    private final RefreshSessionPersistencePort sessions;
    private final SecurityAuditPort audit;
    private final SecurityNotificationOutboxPort outbox;
    private final Clock clock;

    public PasswordSecurityService(CredentialPersistencePort credentials, PasswordVerificationPort verifier,
            PasswordHashPort hasher, RefreshSessionPersistencePort sessions, SecurityAuditPort audit,
            SecurityNotificationOutboxPort outbox, Clock clock) {
        this.credentials = credentials; this.verifier = verifier; this.hasher = hasher; this.sessions = sessions;
        this.audit = audit; this.outbox = outbox; this.clock = clock;
    }

    @Override
    @Transactional
    public void change(Actor actor, String currentPassword, String newPassword) {
        Objects.requireNonNull(actor, "Verified actor is required");
        if (!PasswordPolicy.isValid(newPassword)) throw new IamSecurityException("PASSWORD_POLICY_INVALID");
        var credential = credentials.findByUserId(actor.userId()).orElseThrow(() -> new IamSecurityException("PASSWORD_CHANGE_FAILED"));
        if (!verifier.matches(currentPassword, credential.passwordHash())) throw new IamSecurityException("PASSWORD_CHANGE_FAILED");
        if (verifier.matches(newPassword, credential.passwordHash())) throw new IamSecurityException("PASSWORD_REUSE_NOT_ALLOWED");
        Instant now = clock.instant();
        credentials.updateCredentialHash(actor.userId(), hasher.encode(newPassword), now);
        int revoked = sessions.revokeAllForUser(actor.userId(), actor.sessionId());
        audit.append(new SecurityAuditPort.Event("PASSWORD_CHANGED", actor.userId(), actor.userId(), actor.tenantId(), actor.workspaceId(),
                actor.surface(), valueOrUnknown(actor.correlationId()), valueOrUnknown(actor.traceId()), now,
                Map.of("otherSessionsRevoked", true, "otherSessionsCount", revoked)));
        outbox.enqueuePasswordChanged(credential.email(), actor.surface());
    }

    private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
