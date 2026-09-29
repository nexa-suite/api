package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;

@org.springframework.modulith.NamedInterface(value = "access-contracts", propagate = false)
public interface ResolveCurrentAccessContextUseCase {
	CurrentAccessContext resolve(CurrentAccessRequest request);
}
