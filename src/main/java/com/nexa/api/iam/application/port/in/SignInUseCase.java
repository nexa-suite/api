package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.AuthenticationResult;
import com.nexa.api.iam.application.model.SignInCommand;

public interface SignInUseCase {
	AuthenticationResult signIn(SignInCommand command);
}
