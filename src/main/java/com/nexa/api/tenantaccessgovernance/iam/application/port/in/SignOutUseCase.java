package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.SignOutCommand;

public interface SignOutUseCase {
	void signOut(SignOutCommand command);
}
