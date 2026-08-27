package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;

public interface ResolveCurrentAccessContextUseCase {
	CurrentAccessContext resolve(CurrentAccessRequest request);
}
