package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.RefreshSessionCommand;

public interface RefreshSessionUseCase {
	AuthenticationResult refresh(RefreshSessionCommand command);
}
