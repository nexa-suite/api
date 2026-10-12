package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.Objects;
import java.util.UUID;

/** Explicit local-operator identity used only by internal console/support endpoints. */
public record InternalOperatorContext(UUID operatorId) {
	public InternalOperatorContext {
		Objects.requireNonNull(operatorId, "operatorId");
	}
}
