package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SelectAccessContextCommand;

public interface SelectAccessContextUseCase {
	AuthenticationResult selectAccessContext(SelectAccessContextCommand command);
}
