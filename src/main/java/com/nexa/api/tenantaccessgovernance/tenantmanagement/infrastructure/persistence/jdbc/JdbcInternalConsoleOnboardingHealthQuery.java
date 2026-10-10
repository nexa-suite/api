package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalConsoleOnboardingHealth;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalConsoleOnboardingHealthQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantBusinessDatabaseProvisioningStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantBusinessDatabaseProvisioningStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/** Central read adapter for a bounded, non-sensitive onboarding health projection. */
@Component
public final class JdbcInternalConsoleOnboardingHealthQuery implements InternalConsoleOnboardingHealthQuery {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final TenantBusinessDatabaseProvisioningStatusQuery provisioning;

	public JdbcInternalConsoleOnboardingHealthQuery(JdbcTemplate jdbc,
			PlatformTransactionManager transactionManager,
			TenantBusinessDatabaseProvisioningStatusQuery provisioning) {
		this.jdbc = Objects.requireNonNull(jdbc);
		this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
		this.transactions.setReadOnly(true);
		this.provisioning = Objects.requireNonNull(provisioning);
	}

	@Override
	public List<InternalConsoleOnboardingHealth> listRecent(UUID internalOperatorId, int limit) {
		if (internalOperatorId == null) {
			throw new AccessDeniedException("An authenticated internal operator is required.");
		}
		int boundedLimit = Math.max(1, Math.min(100, limit));
		List<RegistrationStatus> registrations = Objects.requireNonNull(transactions.execute(status -> {
			jdbc.queryForObject("select set_config('app.current_internal_operator_id', ?, true)",
					String.class, internalOperatorId.toString());
			return jdbc.query("select id,status,tenant_id,workspace_id,updated_at "
					+ "from tenant_management.organization_registration "
					+ "order by updated_at desc,id limit ?", registrationMapper(), boundedLimit);
		}));

		List<TenantBusinessDatabaseProvisioningStatus> taskStatuses = provisioning.listRecent(internalOperatorId, boundedLimit);
		Map<WorkspaceKey, TenantBusinessDatabaseProvisioningStatus> byWorkspace = new HashMap<>();
		for (TenantBusinessDatabaseProvisioningStatus task : taskStatuses) {
			byWorkspace.put(new WorkspaceKey(task.tenantId(), task.workspaceId()), task);
		}

		List<InternalConsoleOnboardingHealth> health = new ArrayList<>();
		Set<WorkspaceKey> registrationWorkspaces = new HashSet<>();
		for (RegistrationStatus registration : registrations) {
			TenantBusinessDatabaseProvisioningStatus task = registration.tenantId() == null
					|| registration.workspaceId() == null
				? null
				: byWorkspace.get(new WorkspaceKey(registration.tenantId(), registration.workspaceId()));
			if (task != null) registrationWorkspaces.add(new WorkspaceKey(task.tenantId(), task.workspaceId()));
			Instant updatedAt = task != null && task.updatedAt().isAfter(registration.updatedAt())
				? task.updatedAt() : registration.updatedAt();
			health.add(new InternalConsoleOnboardingHealth(registration.id(), registration.status(),
				registration.tenantId(), registration.workspaceId(),
				task == null ? null : task.status(), task == null ? null : task.attemptCount(),
				task == null ? null : task.registryLifecycleState(), updatedAt));
		}
		for (TenantBusinessDatabaseProvisioningStatus task : taskStatuses) {
			WorkspaceKey workspace = new WorkspaceKey(task.tenantId(), task.workspaceId());
			if (registrationWorkspaces.add(workspace)) {
				health.add(new InternalConsoleOnboardingHealth(null, null, task.tenantId(), task.workspaceId(),
					task.status(), task.attemptCount(), task.registryLifecycleState(), task.updatedAt()));
			}
		}
		return health.stream()
			.sorted(Comparator.comparing(InternalConsoleOnboardingHealth::updatedAt).reversed()
				.thenComparing(row -> row.tenantId() == null ? "" : row.tenantId().toString())
				.thenComparing(row -> row.workspaceId() == null ? "" : row.workspaceId().toString()))
			.limit(boundedLimit)
			.toList();
	}

	private static RowMapper<RegistrationStatus> registrationMapper() {
		return (rs, row) -> mapRegistration(rs);
	}

	private static RegistrationStatus mapRegistration(ResultSet rs) throws SQLException {
		return new RegistrationStatus(rs.getObject("id", UUID.class), rs.getString("status"),
			rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class),
			rs.getTimestamp("updated_at").toInstant());
	}

	private record WorkspaceKey(UUID tenantId, UUID workspaceId) { }
	private record RegistrationStatus(UUID id, String status, UUID tenantId, UUID workspaceId, Instant updatedAt) { }
}
