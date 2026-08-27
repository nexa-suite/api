package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.CurrentSession;
import com.nexa.api.tenantaccessgovernance.iam.application.model.CurrentSessionQuery;

public interface CurrentSessionUseCase {
	CurrentSession currentSession(CurrentSessionQuery query);
}
