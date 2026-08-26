package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationResult;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignInCommand;

public interface SignInUseCase {
	AuthenticationResult signIn(SignInCommand command);
}
