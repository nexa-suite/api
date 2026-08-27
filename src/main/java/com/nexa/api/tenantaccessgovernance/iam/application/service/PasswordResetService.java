package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.RequestPasswordResetCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.ResetPasswordCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.CredentialPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordHashPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordResetPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordResetThrottlePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.RefreshSessionPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityNotificationOutboxPort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.password.PasswordPolicy;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetExpiry;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetRequest;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetRequestId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetTokenHash;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetService implements RequestPasswordResetCommand, ResetPasswordCommand {
    private static final String GENERIC_MESSAGE = "If the account can receive a reset, instructions will be delivered.";
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordResetPersistencePort resets;
    private final PasswordResetThrottlePort throttle;
    private final CredentialPersistencePort credentials;
    private final OpaqueSecurityTokenPort tokens;
    private final PasswordVerificationPort verifier;
    private final PasswordHashPort hasher;
    private final RefreshSessionPersistencePort sessions;
    private final SecurityAuditPort audit;
    private final SecurityNotificationOutboxPort outbox;
    private final Clock clock;
    private final Duration resetTtl;

    public PasswordResetService(PasswordResetPersistencePort resets, PasswordResetThrottlePort throttle,
            CredentialPersistencePort credentials, OpaqueSecurityTokenPort tokens, PasswordVerificationPort verifier,
            PasswordHashPort hasher, RefreshSessionPersistencePort sessions, SecurityAuditPort audit,
            SecurityNotificationOutboxPort outbox, Clock clock,
            @Value("${nexa.security.reset.ttl:PT30M}") Duration resetTtl) {
        this.resets = resets; this.throttle = throttle; this.credentials = credentials; this.tokens = tokens;
        this.verifier = verifier; this.hasher = hasher; this.sessions = sessions; this.audit = audit; this.outbox = outbox;
        this.clock = clock; this.resetTtl = resetTtl;
    }

    @Override
    @Transactional
    public String request(String email, String surface, String clientAddress, String correlationId, String traceId) {
        String normalized = normalizeEmail(email);
        if (surface == null || surface.isBlank()) throw new IamSecurityException("RESET_INVALID");
        Instant now = clock.instant();
        long attempts = throttle.recordAttempt(normalized, clientAddress);
        if (attempts > 3) throw new IamSecurityException("RESET_RATE_LIMITED");

        var credential = credentials.findActiveByNormalizedEmail(normalized);
        String rawToken = tokens.generate();
        if (credential.isEmpty()) {
            verifier.matches(rawToken, DUMMY_PASSWORD_HASH);
        } else {
            for (var pending : resets.findPendingByEmailForUpdate(normalized)) {
                pending.aggregate().revoke();
                resets.save(pending);
            }
            PasswordResetRequest pending = PasswordResetRequest.pending(new PasswordResetRequestId(UUID.randomUUID()),
                    new PasswordResetTokenHash(tokens.sha256(rawToken)), now, new PasswordResetExpiry(now.plus(resetTtl)));
            resets.save(normalized, surface, pending);
            outbox.enqueuePasswordReset(credential.get().email(), surface, rawToken, pending.expiry().value());
        }
        audit.append(new SecurityAuditPort.Event("PASSWORD_RESET_REQUESTED", null, credential.map(c -> c.userId()).orElse(null),
                null, null, surface, valueOrUnknown(correlationId), valueOrUnknown(traceId), now,
                Map.of("accountResponse", "generic")));
        return GENERIC_MESSAGE;
    }

    @Override
    @Transactional
    public void reset(String token, String newPassword, String correlationId, String traceId) {
        if (token == null || token.isBlank() || !PasswordPolicy.isValid(newPassword)) throw new IamSecurityException("RESET_INVALID");
        Instant now = clock.instant();
        var record = resets.findByTokenHashForUpdate(tokens.sha256(token)).orElseThrow(() -> new IamSecurityException("RESET_INVALID"));
        try {
            record.aggregate().expire(now);
            record.aggregate().consume(now);
        } catch (IllegalStateException exception) {
            throw new IamSecurityException("RESET_INVALID");
        }
        var credential = credentials.findActiveByNormalizedEmail(record.normalizedEmail())
                .orElseThrow(() -> new IamSecurityException("RESET_INVALID"));
        credentials.updateCredentialHash(credential.userId(), hasher.encode(newPassword), now);
        sessions.revokeAllForUser(credential.userId(), null);
        resets.save(record);
        audit.append(new SecurityAuditPort.Event("PASSWORD_RESET_COMPLETED", null, credential.userId(), null, null,
                record.surface(), valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("sessionsRevoked", true)));
        outbox.enqueuePasswordChanged(credential.email(), record.surface());
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank() || value.length() > 254 || !value.contains("@")) throw new IamSecurityException("RESET_INVALID");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
