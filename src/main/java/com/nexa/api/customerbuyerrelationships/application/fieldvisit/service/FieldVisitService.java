package com.nexa.api.customerbuyerrelationships.application.fieldvisit.service;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.exception.FieldVisitStaleException;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
public class FieldVisitService {
    private final ClientAccountUseCase customers;
    private final FieldVisitPersistencePort persistence;
    private final Clock clock;
    private final ObjectMapper mapper;
    public FieldVisitService(ClientAccountUseCase customers, FieldVisitPersistencePort persistence, Clock clock, ObjectMapper mapper) {
        this.customers = customers; this.persistence = persistence; this.clock = clock; this.mapper = mapper;
    }
    @Transactional
    public FieldVisitEvidence record(CurrentAccessContext context, String customerId, long version, String key, Command command) {
        authorize(context); context.requirePermission(PermissionKey.CLIENT_MANAGE);
        if (key == null || key.isBlank() || key.length() > 160 || command == null || command.purpose() == null ||
            command.purpose().isBlank() || command.purpose().length() > 500 || command.outcome() == null ||
            command.outcome().isBlank() || command.outcome().length() > 2000 || command.occurredAt() == null ||
            command.occurredAt().isAfter(clock.instant().plusSeconds(300))) throw new IllegalArgumentException("Visit evidence is invalid");
        String tenant = context.tenantId().toString(), workspace = context.workspaceId().toString(), actor = context.membershipId().toString();
        UUID.fromString(customerId);
        persistence.lockCustomer(tenant, workspace, customerId);
        var customer = customers.detail(context, customerId);
        if (!"ACTIVE".equals(customer.status())) throw new AccessPolicyViolation("Customer relationship is not active");
        String hash = hash(customerId, version, command);
        var prior = persistence.replay(tenant, workspace, actor, key, hash);
        if (prior.isPresent()) return prior.get();
        if (customer.version() != version) throw new FieldVisitStaleException();
        var evidence = new FieldVisitEvidence(UUID.randomUUID().toString(), customerId, actor, version,
            command.purpose(), command.outcome(), command.occurredAt(), clock.instant());
        persistence.insert(tenant, workspace, key, hash, evidence);
        return evidence;
    }
    @Transactional(readOnly = true)
    public List<FieldVisitEvidence> list(CurrentAccessContext context, String customerId) {
        authorize(context); customers.detail(context, customerId);
        return persistence.list(context.tenantId().toString(), context.workspaceId().toString(), customerId);
    }
    private void authorize(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Internal workforce authority is required");
        context.requirePermission(PermissionKey.CLIENT_READ);
    }
    private String hash(String customerId, long version, Command command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            (customerId + "\n" + version + "\n" + mapper.writeValueAsString(command)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Visit evidence could not be encoded", failure); }
    }
    public record Command(String purpose, String outcome, Instant occurredAt) { }
}
