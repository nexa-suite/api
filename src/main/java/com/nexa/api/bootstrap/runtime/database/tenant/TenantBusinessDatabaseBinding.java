package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.util.Objects;
import java.util.UUID;

/** Central registry reference to one provisioned Tenant business database. */
public record TenantBusinessDatabaseBinding(
		TenantId tenantId,
		UUID databaseIdentity,
		String credentialSecretReference,
		String verifiedSchemaManifestSha256) {

	public TenantBusinessDatabaseBinding(TenantId tenantId, UUID databaseIdentity,
			String credentialSecretReference) {
		this(tenantId, databaseIdentity, credentialSecretReference, null);
	}

	public TenantBusinessDatabaseBinding {
		tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
		databaseIdentity = Objects.requireNonNull(databaseIdentity, "Database identity is required");
		credentialSecretReference = Objects.requireNonNull(credentialSecretReference,
				"Business database credential reference is required").strip();
		if (credentialSecretReference.isEmpty()) {
			throw new IllegalArgumentException("Business database credential reference is required");
		}
		if (verifiedSchemaManifestSha256 != null
				&& !verifiedSchemaManifestSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Verified Tenant schema manifest digest is invalid");
		}
	}

	@Override
	public String toString() {
		return "TenantBusinessDatabaseBinding[tenantId=" + tenantId + ", databaseIdentity="
				+ databaseIdentity + ", credentialSecretReference=<redacted>]";
	}
}
