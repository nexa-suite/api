package com.nexa.api.customerbuyerrelationships.application.fieldvisit.port;

import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.List;

/** BC-02 field-visit evidence operations exposed to HTTP. */
@org.springframework.modulith.NamedInterface("customer-field-visit-usecases")
public interface FieldVisitUseCase {
    FieldVisitEvidence record(CurrentAccessContext context, String customerId, long version,
                              String idempotencyKey, Command command);

    List<FieldVisitEvidence> listForCustomer(CurrentAccessContext context, String customerId);

    @org.springframework.modulith.NamedInterface("customer-field-visit-usecases")
    record Command(String purpose, String outcome, Instant occurredAt) { }
}
