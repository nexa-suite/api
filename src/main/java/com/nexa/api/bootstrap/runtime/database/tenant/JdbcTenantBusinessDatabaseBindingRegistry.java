package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Central-database adapter. Its JdbcTemplate must use the central registry DataSource. */
public final class JdbcTenantBusinessDatabaseBindingRegistry implements TenantBusinessDatabaseBindingRegistry {
	private static final String FIND_READY_BINDING = """
			SELECT tenant_id, database_identity, credential_secret_reference, verified_schema_manifest_sha256
			FROM tenant_management.tenant_business_database_binding
			WHERE tenant_id = ? AND lifecycle_state = 'READY'
			""";

	private final JdbcTemplate centralRegistryJdbc;

	public JdbcTenantBusinessDatabaseBindingRegistry(JdbcTemplate centralRegistryJdbc) {
		this.centralRegistryJdbc = Objects.requireNonNull(centralRegistryJdbc,
				"Central registry JdbcTemplate is required");
	}

	@Override
	public Optional<TenantBusinessDatabaseBinding> findReadyBinding(TenantId tenantId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		return centralRegistryJdbc.query(FIND_READY_BINDING,
				(result, row) -> new TenantBusinessDatabaseBinding(
						new TenantId(result.getObject("tenant_id", UUID.class)),
						result.getObject("database_identity", UUID.class),
						result.getString("credential_secret_reference"),
						result.getString("verified_schema_manifest_sha256")),
				tenantId.value()).stream().findFirst();
	}
}
