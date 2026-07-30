package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.CurrentSession;
import com.nexa.api.iam.application.model.CurrentSessionQuery;

public interface CurrentSessionUseCase {
	CurrentSession currentSession(CurrentSessionQuery query);
}
