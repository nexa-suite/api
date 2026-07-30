package com.nexa.api.tenantmanagement.application.port.in;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;

public interface ResolveCurrentAccessContextUseCase {
	CurrentAccessContext resolve(CurrentAccessRequest request);
}
