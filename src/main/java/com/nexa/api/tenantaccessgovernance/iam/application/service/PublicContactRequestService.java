package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SubmitPublicContactRequestCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PublicContactRequestPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PublicContactThrottlePort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.publiccontact.PublicContactRequest;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PublicContactRequestService implements SubmitPublicContactRequestCommand {
    private final PublicContactRequestPersistencePort requests;
    private final PublicContactThrottlePort throttle;
    private final SecurityAuditPort audit;
    private final Clock clock;
    private final int maxAttemptsPerHour;

    public PublicContactRequestService(PublicContactRequestPersistencePort requests, PublicContactThrottlePort throttle,
            SecurityAuditPort audit, Clock clock,
            @Value("${nexa.security.public-contact.max-attempts-per-hour:3}") int maxAttemptsPerHour) {
        this.requests = requests;
        this.throttle = throttle;
        this.audit = audit;
        this.clock = clock;
        this.maxAttemptsPerHour = maxAttemptsPerHour;
    }

    @Override
    @Transactional
    public Receipt submit(Command command, String clientAddress, String correlationId, String traceId) {
        if (maxAttemptsPerHour < 1) throw new IllegalStateException("Public contact throttle is misconfigured");
        var now = clock.instant();
        PublicContactRequest request = PublicContactRequest.receive(UUID.randomUUID(), command == null ? null : command.requestType(),
                command == null ? null : command.fullName(), command == null ? null : command.email(),
                command == null ? null : command.companyName(), command == null ? null : command.message(), now);
        if (throttle.recordAttempt(request.email(), clientAddress) > maxAttemptsPerHour) {
            throw new IamSecurityException("PUBLIC_CONTACT_RATE_LIMITED");
        }
        requests.save(request, valueOrUnknown(correlationId), valueOrUnknown(traceId));
        audit.append(new SecurityAuditPort.Event("PUBLIC_CONTACT_REQUEST_SUBMITTED", null, null, null, null, "PUBLIC",
                valueOrUnknown(correlationId), valueOrUnknown(traceId), now,
                Map.of("requestId", request.id().toString(), "requestType", request.type().name(), "status", "RECEIVED")));
        return new Receipt(request.id(), request.type().name(), "RECEIVED", now);
    }

    private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
