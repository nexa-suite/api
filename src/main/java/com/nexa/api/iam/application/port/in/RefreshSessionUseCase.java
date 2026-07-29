package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.AuthenticationResult;
import com.nexa.api.iam.application.model.RefreshSessionCommand;

public interface RefreshSessionUseCase {
	AuthenticationResult refresh(RefreshSessionCommand command);
}
