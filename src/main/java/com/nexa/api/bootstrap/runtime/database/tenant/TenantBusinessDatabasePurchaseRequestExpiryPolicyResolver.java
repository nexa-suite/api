package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Opt-in bridge that checks BC01's current source before every routed transaction,
 * reconciles its local projection, then installs the expected revision transaction-locally.
 */
public final class TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver {
	private static final String EXPECTED_REVISION_SETTING =
			"app.expected_purchase_request_expiry_policy_revision";
	private static final String FIND_SNAPSHOT_SQL = """
			SELECT source_state, source_version, expiry_days, snapshot_revision
			FROM nexa_platform.purchase_request_expiry_policy_snapshot
			WHERE tenant_id = ? AND workspace_id = ?
			""";

	private final OperationalSettingsAccess centralSettings;
	private final TenantBusinessDatabaseRouter router;
	private final TenantBusinessDatabaseAuthority authority;
	private final TenantBusinessDatabasePolicySnapshotWriter writer;

	public TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(OperationalSettingsAccess centralSettings,
			TenantBusinessDatabaseRouter router, TenantBusinessDatabaseAuthority authority,
			TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory writerDataSourceFactory) {
		this.centralSettings = Objects.requireNonNull(centralSettings, "Central BC01 settings port is required");
		this.router = Objects.requireNonNull(router, "Tenant business database router is required");
		this.authority = Objects.requireNonNull(authority, "Central Tenant database authority is required");
		this.writer = new TenantBusinessDatabasePolicySnapshotWriter(
				this.authority,
				Objects.requireNonNull(writerDataSourceFactory, "Dedicated policy snapshot writer factory is required"));
	}

	public <T> T inTransaction(CurrentAccessContext accessContext, TenantBusinessDatabaseWork<T> work) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		Objects.requireNonNull(work, "Tenant business work is required");
		OperationalSettingsAccess.PurchaseRequestExpiryPolicySource source = centralSettings
				.findPurchaseRequestExpiryPolicy(accessContext.tenantId(), accessContext.workspaceId())
				.orElseThrow(TenantBusinessDatabasePolicySnapshotConflictException::new);
		TenantPurchaseRequestExpiryPolicySnapshot current = readSnapshot(accessContext);
		if (current == null || !current.matches(source)) {
			current = writer.refresh(accessContext, source, current == null ? 0 : current.snapshotRevision());
		}

		return router.inTransaction(accessContext, jdbc -> {
			TenantPurchaseRequestExpiryPolicySnapshot active = readSnapshot(jdbc, accessContext);
			if (active == null || !active.matches(source)) {
				throw new TenantBusinessDatabasePolicySnapshotConflictException();
			}
			jdbc.queryForObject("select set_config('" + EXPECTED_REVISION_SETTING + "', ?, true)", String.class,
					Long.toString(active.snapshotRevision()));
			return work.execute(jdbc);
		});
	}

	/** Checks current manifest evidence and dedicated writer credentials before advertising wallet capability. */
	public void verifyWalletCapabilityReady(CurrentAccessContext accessContext) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		TenantBusinessDatabaseBinding binding = authority.requireReadyBinding(accessContext);
		String currentDigest = TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest();
		if (!currentDigest.equals(binding.verifiedSchemaManifestSha256())) {
			throw new TenantBusinessDatabaseSchemaManifestMismatchException();
		}
		writer.verifyConfigured(binding);
	}

	private TenantPurchaseRequestExpiryPolicySnapshot readSnapshot(CurrentAccessContext accessContext) {
		return router.inTransaction(accessContext, jdbc -> readSnapshot(jdbc, accessContext));
	}

	private static TenantPurchaseRequestExpiryPolicySnapshot readSnapshot(JdbcTemplate jdbc,
			CurrentAccessContext accessContext) {
		return jdbc.query(FIND_SNAPSHOT_SQL, (rs, row) -> new TenantPurchaseRequestExpiryPolicySnapshot(
				accessContext.tenantId(), accessContext.workspaceId(),
				OperationalSettingsAccess.SourceState.valueOf(rs.getString("source_state")),
				(rs.getObject("source_version") == null ? null : rs.getLong("source_version")),
				rs.getInt("expiry_days"), rs.getLong("snapshot_revision")),
			accessContext.tenantId().value(), accessContext.workspaceId().value()).stream().findFirst().orElse(null);
	}
}
