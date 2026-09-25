package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

/** Binds the authenticated identity for one transaction-local access-context discovery query. */
public interface AccessContextDiscoveryScopePort {
	void bindTrustedIdentity(UserAccountId userAccountId);
}
