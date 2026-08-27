package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Persistence intent for credentials; password policy and orchestration stay in Application. */
public interface CredentialPersistencePort {
    Optional<CredentialRecord> findByUserId(UUID userId);
    Optional<CredentialRecord> findActiveByNormalizedEmail(String normalizedEmail);
    void updateCredentialHash(UUID userId, String passwordHash, java.time.Instant changedAt);

    record CredentialRecord(UUID userId, String email, String passwordHash) { }
}
