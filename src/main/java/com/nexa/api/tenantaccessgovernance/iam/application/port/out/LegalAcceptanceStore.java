package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels.Evidence;
import java.util.Optional;
import java.util.UUID;

public interface LegalAcceptanceStore {
    Optional<Evidence> find(UUID userId, String documentId, String version);
    void insertIfAbsent(UUID userId, String documentId, String version, String digest);
}
