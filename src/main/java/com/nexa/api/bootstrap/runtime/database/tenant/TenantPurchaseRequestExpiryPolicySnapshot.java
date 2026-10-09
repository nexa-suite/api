package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import java.util.Objects;

/** Non-authoritative Tenant-local projection of the centrally verified expiry policy. */
public record TenantPurchaseRequestExpiryPolicySnapshot(
		TenantId tenantId,
		WorkspaceId workspaceId,
		OperationalSettingsAccess.SourceState sourceState,
		Long sourceVersion,
		int expiryDays,
		long snapshotRevision) {

	public TenantPurchaseRequestExpiryPolicySnapshot {
		tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
		workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
		sourceState = Objects.requireNonNull(sourceState, "Central source state is required");
		if (snapshotRevision < 1) throw new IllegalArgumentException("Snapshot revision must be positive");
		if (sourceState == OperationalSettingsAccess.SourceState.PRESENT) {
			if (sourceVersion == null || sourceVersion < 0 || expiryDays < 1 || expiryDays > 7) {
				throw new IllegalArgumentException("Present expiry policy snapshot is invalid");
			}
		} else if (sourceVersion != null || expiryDays != 3) {
			throw new IllegalArgumentException("Confirmed absence must preserve the three-day fallback");
		}
	}

	public boolean matches(OperationalSettingsAccess.PurchaseRequestExpiryPolicySource source) {
		return tenantId.equals(source.tenantId()) && workspaceId.equals(source.workspaceId())
				&& sourceState == source.sourceState() && Objects.equals(sourceVersion, source.sourceVersion())
				&& expiryDays == source.expiryDays();
	}
}
