package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** Writes only the non-authoritative Tenant-local expiry-policy projection using its dedicated DB login. */
final class TenantBusinessDatabasePolicySnapshotWriter {
	private static final String WRITER_ROLE = "nexa_policy_snapshot_writer";
	private static final String UPSERT_SQL = """
			INSERT INTO nexa_platform.purchase_request_expiry_policy_snapshot
			    (tenant_id, workspace_id, source_state, source_version, expiry_days, snapshot_revision, observed_at)
			VALUES (?, ?, ?, ?, ?, 1, current_timestamp)
			ON CONFLICT (tenant_id, workspace_id) DO UPDATE
			   SET source_state = EXCLUDED.source_state,
			       source_version = EXCLUDED.source_version,
			       expiry_days = EXCLUDED.expiry_days,
			       snapshot_revision = purchase_request_expiry_policy_snapshot.snapshot_revision + 1,
			       observed_at = current_timestamp
			 WHERE purchase_request_expiry_policy_snapshot.snapshot_revision = ?
			   AND (
			       purchase_request_expiry_policy_snapshot.source_state IS DISTINCT FROM EXCLUDED.source_state
			       OR (purchase_request_expiry_policy_snapshot.source_state = 'PRESENT'
			           AND EXCLUDED.source_state = 'PRESENT'
			           AND EXCLUDED.source_version > purchase_request_expiry_policy_snapshot.source_version)
			   )
			RETURNING source_state, source_version, expiry_days, snapshot_revision
			""";

	private final TenantBusinessDatabaseAuthority authority;
	private final TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory dataSourceFactory;

	TenantBusinessDatabasePolicySnapshotWriter(TenantBusinessDatabaseAuthority authority,
			TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory dataSourceFactory) {
		this.authority = Objects.requireNonNull(authority, "Central Tenant database authority is required");
		this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory,
				"Dedicated Tenant policy-snapshot writer DataSource factory is required");
	}

	TenantPurchaseRequestExpiryPolicySnapshot refresh(CurrentAccessContext accessContext,
			OperationalSettingsAccess.PurchaseRequestExpiryPolicySource source, long expectedSnapshotRevision) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		Objects.requireNonNull(source, "Centrally read expiry-policy source is required");
		if (expectedSnapshotRevision < 0) {
			throw new IllegalArgumentException("Expected Tenant policy snapshot revision cannot be negative");
		}
		if (!source.tenantId().equals(accessContext.tenantId())
				|| !source.workspaceId().equals(accessContext.workspaceId())) {
			throw new TenantBusinessDatabasePolicySnapshotConflictException();
		}

		TenantBusinessDatabaseBinding binding = authority.requireReadyBinding(accessContext);
		DataSource writerDataSource = dataSourceFactory.create(binding);
		TenantBusinessDatabaseIdentityCheckingDataSource identityChecked =
				new TenantBusinessDatabaseIdentityCheckingDataSource(writerDataSource, binding);
		DataSource scoped = RlsScopedDataSource.forVerifiedTenant(
				identityChecked.forVerifiedWorkspace(accessContext.workspaceId()), accessContext);
		JdbcTemplate jdbc = new JdbcTemplate(scoped);
		TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(scoped));
		TenantPurchaseRequestExpiryPolicySnapshot written = transaction.execute(status -> {
			String currentRole = jdbc.queryForObject("select current_user", String.class);
			if (!WRITER_ROLE.equals(currentRole)) {
				throw new IllegalStateException("Tenant policy snapshots require the dedicated writer database role");
			}
			return jdbc.query(UPSERT_SQL, (rs, row) -> new TenantPurchaseRequestExpiryPolicySnapshot(
					accessContext.tenantId(), accessContext.workspaceId(),
					OperationalSettingsAccess.SourceState.valueOf(rs.getString("source_state")),
					(rs.getObject("source_version") == null ? null : rs.getLong("source_version")),
					rs.getInt("expiry_days"), rs.getLong("snapshot_revision")),
				accessContext.tenantId().value(), accessContext.workspaceId().value(), source.sourceState().name(),
				source.sourceVersion(), source.expiryDays(), expectedSnapshotRevision)
				.stream().findFirst().orElseThrow(TenantBusinessDatabasePolicySnapshotConflictException::new);
		});
		return Objects.requireNonNull(written, "Tenant policy snapshot transaction returned no value");
	}
}
