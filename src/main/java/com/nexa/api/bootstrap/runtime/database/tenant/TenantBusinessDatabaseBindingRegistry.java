package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.util.Optional;

/** Reads the Tenant-to-database binding from central Tenant governance storage. */
public interface TenantBusinessDatabaseBindingRegistry {
	Optional<TenantBusinessDatabaseBinding> findReadyBinding(TenantId tenantId);
}
