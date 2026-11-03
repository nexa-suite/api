package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.shared.application.port.out.TenantBusinessDataBindingGuard;

import java.util.Optional;
import java.util.UUID;

/** Reads the Tenant-to-database binding from central Tenant governance storage. */
public interface TenantBusinessDatabaseBindingRegistry extends TenantBusinessDataBindingGuard {
	Optional<TenantBusinessDatabaseBinding> findReadyBinding(TenantId tenantId);

	/** Ready callback-worker binding for one exact active Tenant Workspace. Unknown registries fail closed. */
	default Optional<PaymentCallbackBinding> findReadyPaymentCallbackBinding(TenantId tenantId, WorkspaceId workspaceId) {
		return Optional.empty();
	}

	/** Current Buyer membership authority from central governance; unknown registries fail closed. */
	default boolean isActiveBuyerMembership(TenantId tenantId, WorkspaceId workspaceId, UUID membershipId) {
		return false;
	}

	record PaymentCallbackBinding(TenantBusinessDatabaseBinding binding, WorkspaceId workspaceId) { }

	/**
	 * Returns whether a Tenant has any database binding, including non-READY states.
	 * Background work must not interpret a suspended or incomplete binding as a
	 * legacy central-database Tenant.
	 */
	default boolean hasAnyBinding(TenantId tenantId) {
		// Unknown registries cannot prove that central storage still owns business
		// data. Conservatively keep the existing Tenant scope out of the legacy sweep.
		return true;
	}

	/** The caller must hold a central-registry transaction; unknown registries fail closed. */
	default boolean lockAndCheckNoBinding(TenantId tenantId) {
		return false;
	}
}
