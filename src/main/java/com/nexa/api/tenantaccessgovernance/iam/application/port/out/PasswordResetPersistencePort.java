package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetRequest;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset.PasswordResetStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Locking persistence boundary for the PasswordResetRequest workflow. */
public interface PasswordResetPersistencePort {
    List<ResetRecord> findPendingByEmailForUpdate(String normalizedEmail);
    Optional<ResetRecord> findByTokenHashForUpdate(String tokenHash);
    void save(String normalizedEmail, String surface, PasswordResetRequest request);
    void save(ResetRecord record);

    record ResetRecord(UUID id, String normalizedEmail, String surface, PasswordResetRequest aggregate) {
        public ResetRecord {
            if (id == null || normalizedEmail == null || surface == null || aggregate == null) throw new IllegalArgumentException("Reset record is required");
        }
        public PasswordResetStatus status() { return aggregate.status(); }
        public Instant createdAt() { return aggregate.createdAt(); }
    }
}
