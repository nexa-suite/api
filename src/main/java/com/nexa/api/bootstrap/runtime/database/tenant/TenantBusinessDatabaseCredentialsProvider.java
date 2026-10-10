package com.nexa.api.bootstrap.runtime.database.tenant;

/** Resolves only the Tenant business-database secret named by the central registry. */
@FunctionalInterface
public interface TenantBusinessDatabaseCredentialsProvider {
	TenantBusinessDatabaseCredentials requireCredentials(String credentialSecretReference);
}
