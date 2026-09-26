package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IdentitySignInResult;

public interface IdentitySignInUseCase {
	IdentitySignInResult identitySignIn(IdentitySignInCommand command);
}
