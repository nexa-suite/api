package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.SignOutCommand;

public interface SignOutUseCase {
	void signOut(SignOutCommand command);
}
