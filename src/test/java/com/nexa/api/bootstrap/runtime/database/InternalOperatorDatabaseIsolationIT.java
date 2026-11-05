package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc.InternalOperatorJdbcOperations;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class InternalOperatorDatabaseIsolationIT extends PostgresIntegrationSupport {
	@Autowired
	private InternalOperatorJdbcOperations internalOperatorJdbc;

	@Test
	void runtimeCannotMutateChangeFeedBySettingMaintenanceGuc() throws Exception {
		try (Connection runtime = openRuntimeConnection();
			 Statement statement = runtime.createStatement()) {
			statement.execute("SELECT set_config('nexa.change_feed.maintenance', 'on', false)");

			assertThatThrownBy(() -> statement.executeUpdate(
					"UPDATE integration.change_event SET event_type = event_type WHERE false"))
					.as("a caller-controlled maintenance GUC must not grant direct event updates")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> statement.executeUpdate(
					"DELETE FROM integration.change_event WHERE false"))
					.as("retention must remain the only runtime path that deletes expired events")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> statement.execute("TRUNCATE integration.change_event"))
				.as("runtime must not bypass append-only triggers through TRUNCATE")
				.isInstanceOf(java.sql.SQLException.class)
				.hasMessageContaining("permission denied");

			try (ResultSet result = statement.executeQuery("""
					SELECT has_table_privilege(current_user, 'integration.change_event', 'SELECT'),
					       has_table_privilege(current_user, 'integration.change_event', 'INSERT'),
					       has_function_privilege(current_user,
					           'integration.purge_expired_change_events(integer)', 'EXECUTE')
					""")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).isTrue();
				assertThat(result.getBoolean(2)).isTrue();
				assertThat(result.getBoolean(3)).isTrue();
			}
		}
	}

	@Test
	void changeFeedReadAndAppendRequireTheMatchingTenantWorkspaceScope() throws Exception {
		UUID tenantId = UUID.fromString(tenantId());
		UUID workspaceId = UUID.fromString(workspaceId());
		UUID eventId = UUID.randomUUID();
		try (Connection runtime = openRuntimeConnection()) {
			runtime.setAutoCommit(false);
			assertThat(changeEventCount(runtime, eventId)).as("missing scope must fail closed").isZero();

			setScope(runtime, UUID.randomUUID(), UUID.randomUUID());
			Savepoint deniedInsert = runtime.setSavepoint();
			assertThatThrownBy(() -> insertChangeEvent(runtime, eventId, tenantId, workspaceId))
					.as("runtime cannot append an event for a different Tenant/Workspace")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("row-level security");
			runtime.rollback(deniedInsert);

			setScope(runtime, tenantId, workspaceId);
			insertChangeEvent(runtime, eventId, tenantId, workspaceId);
			assertThat(changeEventCount(runtime, eventId)).as("matching scope can append and read its event").isEqualTo(1);

			setScope(runtime, UUID.randomUUID(), UUID.randomUUID());
			assertThat(changeEventCount(runtime, eventId)).as("foreign Tenant/Workspace scope cannot read the event").isZero();
			runtime.rollback();
		}
	}

	@Test
	void runtimeSupportPrivilegesMatchCompanyOwnerWhileOperatorRoleKeepsOperatorAccess() throws Exception {
		UUID tenantId = UUID.fromString(tenantId());
		UUID workspaceId = UUID.fromString(workspaceId());
		UUID requestId = UUID.randomUUID();
		try (Connection runtime = openRuntimeConnection()) {
			runtime.setAutoCommit(false);
			setScope(runtime, tenantId, workspaceId);
			try (PreparedStatement setOperator = runtime.prepareStatement(
					"SELECT set_config('app.current_internal_operator_id', ?, false)")) {
				setOperator.setString(1, UUID.randomUUID().toString());
				setOperator.execute();
			}

			Savepoint forgedSupportRequest = runtime.setSavepoint();
			assertThatThrownBy(() -> runtime.createStatement().executeUpdate("""
					INSERT INTO iam.internal_support_request
						(id,tenant_id,workspace_id,resource_type,resource_id,requested_by_operator_id,status,
						 expires_at,created_at,updated_at,version)
					VALUES ('%s','%s','%s','SALES_ORDER','%s','%s','AWAITING_OWNER_CONSENT',
						 current_timestamp + interval '30 minutes',current_timestamp,current_timestamp,0)
					""".formatted(requestId, tenantId, workspaceId, UUID.randomUUID(), UUID.randomUUID())))
					.as("Company Owner runtime cannot create an operator support request")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("permission denied");
			runtime.rollback(forgedSupportRequest);

			Savepoint forgedOperatorApproval = runtime.setSavepoint();
			assertThatThrownBy(() -> runtime.createStatement().executeUpdate("""
					UPDATE iam.internal_support_request SET approved_by_operator_id='%s' WHERE id='%s'
					""".formatted(UUID.randomUUID(), requestId)))
					.as("runtime cannot set the independent operator approver")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("permission denied");
			runtime.rollback(forgedOperatorApproval);

			Savepoint auditRead = runtime.setSavepoint();
			assertThatThrownBy(() -> runtime.createStatement().executeQuery(
					"SELECT id FROM iam.internal_support_audit_event"))
					.as("runtime can append owner audit but cannot read internal support audit")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("permission denied");
			runtime.rollback(auditRead);

			Savepoint forgedOperatorAudit = runtime.setSavepoint();
			assertThatThrownBy(() -> insertSupportAudit(runtime, requestId, tenantId, workspaceId,
					"OWNER_CONSENTED", "INTERNAL_OPERATOR"))
					.as("a valid caller-set operator GUC does not authorize runtime operator audit")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("row-level security");
			runtime.rollback(forgedOperatorAudit);

			Savepoint forgedOwnerApprovalAudit = runtime.setSavepoint();
			assertThatThrownBy(() -> insertSupportAudit(runtime, requestId, tenantId, workspaceId,
					"APPROVED", "COMPANY_OWNER"))
					.as("runtime owner audit is limited to consent and revocation actions")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("row-level security");
			runtime.rollback(forgedOwnerApprovalAudit);

			Savepoint ownerAudit = runtime.setSavepoint();
			assertThatThrownBy(() -> insertSupportAudit(runtime, requestId, tenantId, workspaceId,
					"OWNER_CONSENTED", "COMPANY_OWNER"))
					.as("owner audit passes RLS; missing synthetic parent is rejected by the foreign key")
					.isInstanceOf(java.sql.SQLException.class)
					.hasMessageContaining("foreign key constraint");
			runtime.rollback(ownerAudit);
			runtime.rollback();
		}

		internalOperatorJdbc.readOnly(operatorJdbc -> {
			assertThat(operatorJdbc.queryForObject("SELECT current_user", String.class))
					.isEqualTo("nexa_internal_operator");
				assertThat(operatorJdbc.queryForObject("""
					SELECT has_column_privilege('nexa_internal_operator',
					           'iam.internal_support_request', 'approved_by_operator_id', 'UPDATE')
					   AND has_column_privilege('nexa_internal_operator',
					           'iam.internal_support_audit_event', 'actor_type', 'INSERT')
					""", Boolean.class)).isTrue();
				return Boolean.TRUE;
		});
	}

	private static void setScope(Connection connection, UUID tenantId, UUID workspaceId) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT set_config('app.current_tenant_id', ?, false),
				       set_config('app.current_workspace_id', ?, false)
				""")) {
			statement.setString(1, tenantId.toString());
			statement.setString(2, workspaceId.toString());
			statement.execute();
		}
	}

	private static long changeEventCount(Connection connection, UUID eventId) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT count(*) FROM integration.change_event WHERE event_id=?")) {
			statement.setObject(1, eventId);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getLong(1);
			}
		}
	}

	private static void insertChangeEvent(Connection connection, UUID eventId, UUID tenantId, UUID workspaceId)
			throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO integration.change_event
					(event_id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,audiences,occurred_at,expires_at)
				VALUES (?,?,?,'scope-test',?,'scope-test.event',ARRAY['OWNER'],?,?)
				""")) {
			statement.setObject(1, eventId);
			statement.setObject(2, tenantId);
			statement.setObject(3, workspaceId);
			statement.setObject(4, UUID.randomUUID());
			statement.setObject(5, java.sql.Timestamp.from(Instant.now()));
			statement.setObject(6, java.sql.Timestamp.from(Instant.now().plusSeconds(300)));
			statement.executeUpdate();
		}
	}

	private static void insertSupportAudit(Connection connection, UUID requestId, UUID tenantId, UUID workspaceId,
			String action, String actorType) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO iam.internal_support_audit_event
					(id,support_request_id,action,actor_type,actor_id,actor_membership_id,tenant_id,workspace_id,
					 resource_type,resource_id,expires_at,occurred_at)
				VALUES (?,?,?,?,?,?, ?,?,'SALES_ORDER',?,?,?)
				""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, requestId);
			statement.setString(3, action);
			statement.setString(4, actorType);
			statement.setObject(5, UUID.randomUUID());
			statement.setObject(6, "COMPANY_OWNER".equals(actorType) ? UUID.randomUUID() : null);
			statement.setObject(7, tenantId);
			statement.setObject(8, workspaceId);
			statement.setObject(9, UUID.randomUUID());
			statement.setObject(10, java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
			statement.setObject(11, java.sql.Timestamp.from(Instant.now()));
			statement.executeUpdate();
		}
	}

	@Test
	void validOperatorSettingDoesNotGiveRuntimeLoginInternalOperatorAccess() throws Exception {
		UUID registrationId = UUID.randomUUID();
		UUID operatorId = UUID.randomUUID();
		insertDraftRegistration(registrationId);
		try {
			try (Connection runtime = openRuntimeConnection();
				 PreparedStatement setOperator = runtime.prepareStatement(
						"SELECT set_config('app.current_internal_operator_id', ?, false)")) {
				setOperator.setString(1, operatorId.toString());
				setOperator.execute();
				try (PreparedStatement query = runtime.prepareStatement(
						"SELECT id FROM tenant_management.organization_registration WHERE id = ?")) {
					query.setObject(1, registrationId);
					try (ResultSet rows = query.executeQuery()) {
						assertThat(rows.next())
								.as("a caller-set valid operator UUID must not bypass the database principal gate")
								.isFalse();
					}
				}
			}

			String operatorRole = internalOperatorJdbc.readOnly(db -> db.queryForObject("SELECT current_user", String.class));
			assertThat(operatorRole)
					.as("the dedicated connection must switch into the narrow operator database role")
					.isEqualTo("nexa_internal_operator");
			UUID visibleRegistration = internalOperatorJdbc.readOnly(db -> {
				db.queryForObject("SELECT set_config('app.current_internal_operator_id', ?, true)",
						String.class, operatorId.toString());
				return db.queryForObject(
						"SELECT id FROM tenant_management.organization_registration WHERE id = ?",
						(rs, rowNumber) -> rs.getObject("id", UUID.class), registrationId);
			});
			assertThat(visibleRegistration)
					.as("the dedicated role can read the bounded onboarding metadata projection")
					.isEqualTo(registrationId);

			assertThatThrownBy(() -> internalOperatorJdbc.readOnly(db -> db.queryForObject(
					"SELECT founder_email FROM tenant_management.organization_registration WHERE id = ?",
					String.class, registrationId)))
					.as("the dedicated role cannot read onboarding contact data")
					.isInstanceOf(DataAccessException.class);
		} finally {
			jdbc.update("DELETE FROM tenant_management.organization_registration WHERE id = ?", registrationId);
		}
	}

	@Test
	void operatorRoleHasNoMaintenanceOrSequencePrivilegesInAnyApplicationSchema() {
		Integer unapprovedMaintenanceGrants = internalOperatorJdbc.readOnly(db -> db.queryForObject("""
				SELECT count(*)
				  FROM pg_class relation
				  JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
				 WHERE schema_row.nspname !~ '^pg_'
				   AND schema_row.nspname <> 'information_schema'
				   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
				   AND has_table_privilege('nexa_internal_operator', relation.oid, 'MAINTAIN')
				""", Integer.class));
		Integer unapprovedSequenceGrants = internalOperatorJdbc.readOnly(db -> db.queryForObject("""
				SELECT count(*)
				  FROM pg_class sequence
				  JOIN pg_namespace schema_row ON schema_row.oid = sequence.relnamespace
				 WHERE schema_row.nspname !~ '^pg_'
				   AND schema_row.nspname <> 'information_schema'
				   AND sequence.relkind = 'S'
				   AND (has_sequence_privilege('nexa_internal_operator', sequence.oid, 'USAGE')
				     OR has_sequence_privilege('nexa_internal_operator', sequence.oid, 'SELECT')
				     OR has_sequence_privilege('nexa_internal_operator', sequence.oid, 'UPDATE'))
				""", Integer.class));

		assertThat(unapprovedMaintenanceGrants)
				.as("the operator's approved V178 projection excludes PostgreSQL maintenance authority")
				.isZero();
		assertThat(unapprovedSequenceGrants)
				.as("operator workflows use UUIDs and do not need central sequence access")
				.isZero();
	}

	@Test
	void operatorRoleAndLoginCannotAccessUnapprovedSchemasOrPrivilegedFunctions() {
		Integer unapprovedSchemaUsage = internalOperatorJdbc.readOnly(db -> db.queryForObject("""
				SELECT count(*)
				  FROM pg_namespace schema_row
				 WHERE schema_row.nspname !~ '^pg_'
				   AND schema_row.nspname <> 'information_schema'
				   AND schema_row.nspname NOT IN ('iam', 'tenant_management', 'public')
				   AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'USAGE')
				""", Integer.class));
		Integer executableSecurityDefinerFunctions = internalOperatorJdbc.readOnly(db -> db.queryForObject("""
				SELECT count(*)
				  FROM pg_proc function_row
				  JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
				 WHERE schema_row.nspname !~ '^pg_'
				   AND schema_row.nspname <> 'information_schema'
				   AND function_row.prosecdef
				   AND has_schema_privilege('nexa_internal_operator', schema_row.oid, 'USAGE')
				   AND has_function_privilege('nexa_internal_operator', function_row.oid, 'EXECUTE')
				""", Integer.class));
		Integer loginRelationAccess = internalOperatorJdbc.readOnly(db -> db.queryForObject("""
				SELECT count(*)
				  FROM pg_class relation
				  JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
				 WHERE schema_row.nspname !~ '^pg_'
				   AND schema_row.nspname <> 'information_schema'
				   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
				   AND (has_table_privilege('nexa_internal_operator_login', relation.oid, 'SELECT')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'INSERT')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'UPDATE')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'DELETE')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'TRUNCATE')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'REFERENCES')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'TRIGGER')
				     OR has_table_privilege('nexa_internal_operator_login', relation.oid, 'MAINTAIN')
				     OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'SELECT')
				     OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'INSERT')
				     OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'UPDATE')
				     OR has_any_column_privilege('nexa_internal_operator_login', relation.oid, 'REFERENCES'))
				""", Integer.class));

		assertThat(unapprovedSchemaUsage)
				.as("the internal operator must only see the two approved central schemas and public")
				.isZero();
		assertThat(executableSecurityDefinerFunctions)
				.as("the internal operator must not execute application SECURITY DEFINER functions")
				.isZero();
		assertThat(loginRelationAccess)
				.as("the login principal must rely on SET ROLE and hold no application relation grants")
				.isZero();
	}

	private void insertDraftRegistration(UUID id) {
		String suffix = id.toString();
		jdbc.update("""
				INSERT INTO tenant_management.organization_registration
				    (id, legal_name, display_name, normalized_legal_name, operation_category,
				     storage_site_name, storage_site_address, founder_email, founder_display_name,
				     workspace_name, workspace_slug, reference_plan, terms_version, terms_accepted_at,
				     status, status_token_hash, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 'B2B', 'Site', 'Test address', ?, 'Founder', 'Workspace',
				        ?, 'Starter', 'test-v1', current_timestamp, 'DRAFT', repeat('a', 64),
				        current_timestamp, current_timestamp, 0)
				""",
			id,
			"Operator health " + suffix,
			"Operator health " + suffix,
			"operator health " + suffix,
			"operator-health-" + suffix + "@example.test",
			"operator-health-" + suffix);
	}
}
