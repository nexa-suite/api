package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.util.UUID;

/** Resolves a dedicated snapshot-writer login without receiving the runtime secret reference. */
@FunctionalInterface
public interface TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider {
	TenantBusinessDatabaseCredentials requireWriterCredentials(TenantId tenantId, UUID databaseIdentity);
}
