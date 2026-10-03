package com.nexa.api.customerbuyerrelationships.application.fieldvisit.port;

import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import java.util.List;
import java.util.Optional;

public interface FieldVisitPersistencePort {
    void lockCustomer(String tenant, String workspace, String customer);
    Optional<FieldVisitEvidence> replay(String tenant, String workspace, String membership, String key, String hash);
    void insert(String tenant, String workspace, String key, String hash, FieldVisitEvidence evidence);
    List<FieldVisitEvidence> list(String tenant, String workspace, String customer);
}
