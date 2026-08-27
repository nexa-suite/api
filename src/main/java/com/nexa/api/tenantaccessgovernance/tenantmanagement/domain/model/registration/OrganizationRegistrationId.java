package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.registration;

import java.util.Objects;
import java.util.UUID;

public record OrganizationRegistrationId(UUID value) {
    public OrganizationRegistrationId { Objects.requireNonNull(value, "Organization registration id is required"); }
}
