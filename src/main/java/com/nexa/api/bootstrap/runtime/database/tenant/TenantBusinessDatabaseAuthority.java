package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Revalidates a current central membership and resolves its ready business-database binding. */
public interface TenantBusinessDatabaseAuthority {
	TenantBusinessDatabaseBinding requireReadyBinding(CurrentAccessContext presentedAccessContext);
}
