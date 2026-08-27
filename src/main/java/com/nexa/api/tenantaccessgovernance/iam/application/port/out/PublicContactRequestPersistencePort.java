package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.publiccontact.PublicContactRequest;

/** Persistence intent for public contact/demo intake. */
public interface PublicContactRequestPersistencePort {
    void save(PublicContactRequest request, String correlationId, String traceId);
}
