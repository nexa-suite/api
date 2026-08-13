package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.domain.model.publiccontact.PublicContactRequest;

/** Persistence intent for public contact/demo intake. */
public interface PublicContactRequestPersistencePort {
    void save(PublicContactRequest request, String correlationId, String traceId);
}
