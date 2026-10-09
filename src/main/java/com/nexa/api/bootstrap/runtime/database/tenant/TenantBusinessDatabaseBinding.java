package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.util.Objects;
import java.util.UUID;

/** Central registry reference to one provisioned Tenant business database. */
public record TenantBusinessDatabaseBinding(
		TenantId tenantId,
		UUID databaseIdentity,
		String credentialSecretReference) {

	public TenantBusinessDatabaseBinding {
		tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
		databaseIdentity = Objects.requireNonNull(databaseIdentity, "Database identity is required");
		credentialSecretReference = Objects.requireNonNull(credentialSecretReference,
				"Business database credential reference is required").strip();
		if (credentialSecretReference.isEmpty()) {
			throw new IllegalArgumentException("Business database credential reference is required");
		}
	}

	@Override
	public String toString() {
		return "TenantBusinessDatabaseBinding[tenantId=" + tenantId + ", databaseIdentity="
				+ databaseIdentity + ", credentialSecretReference=<redacted>]";
	}
}
