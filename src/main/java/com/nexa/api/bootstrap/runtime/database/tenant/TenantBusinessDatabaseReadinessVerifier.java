package com.nexa.api.bootstrap.runtime.database.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.UUID;

/** Shared physical identity and least-privilege checks; local and Neon policies stay explicit. */
public final class TenantBusinessDatabaseReadinessVerifier {
    private TenantBusinessDatabaseReadinessVerifier() { }
	public static void verifyResumableDatabase(Connection connection, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity) throws SQLException {
		boolean historyExists = exists(connection,
				"SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
		boolean identityExists = exists(connection,
				"SELECT to_regclass('nexa_platform.tenant_business_database_identity') IS NOT NULL");
		if (!historyExists && !identityExists) {
			try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("""
					 SELECT EXISTS (
					     SELECT 1 FROM pg_namespace
					     WHERE nspname NOT IN ('pg_catalog', 'information_schema', 'public', 'pg_toast')
					       AND nspname !~ '^pg_(temp|toast_temp)_[0-9]+$'
					 ) OR EXISTS (
					     SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
					     WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')
				     ) OR EXISTS (
				         SELECT 1 FROM pg_proc WHERE pronamespace='public'::regnamespace
				     ) OR EXISTS (
				         SELECT 1 FROM pg_type
				         WHERE typnamespace='public'::regnamespace AND typrelid=0 AND typtype <> 'p'
				     ) OR EXISTS (
				         SELECT 1 FROM pg_extension WHERE extnamespace='public'::regnamespace
					 )
					 """)) {
				result.next();
				if (result.getBoolean(1)) {
					throw new IllegalStateException("Tenant database is not empty and has no trusted migration identity");
				}
			}
			return;
		}
		if (!historyExists || !identityExists) {
			throw new IllegalStateException("Tenant database migration history and identity are inconsistent");
		}
		verifyPersistedIdentity(connection, tenantId, databaseIdentity, false);
		if (exists(connection, "SELECT to_regclass('nexa_platform.tenant_workspace_scope_anchor') IS NOT NULL")) {
			verifyPersistedWorkspace(connection, tenantId, workspaceId, false);
		}
	}

	public static void verifyIdentityAndWorkspace(Connection connection, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity) throws SQLException {
		verifyPersistedIdentity(connection, tenantId, databaseIdentity, true);
		verifyPersistedWorkspace(connection, tenantId, workspaceId, true);
	}

	private static void verifyPersistedIdentity(Connection connection, UUID tenantId, UUID databaseIdentity,
			boolean requirePresent) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT tenant_id, database_identity
				 FROM nexa_platform.tenant_business_database_identity
				 WHERE singleton = TRUE
			 """)) {
			if (!result.next()) {
				if (requirePresent) throw new IllegalStateException("Tenant database identity is missing");
				return;
			}
			if (!tenantId.equals(result.getObject("tenant_id", UUID.class))
					|| !databaseIdentity.equals(result.getObject("database_identity", UUID.class))
					|| result.next()) {
				throw new IllegalStateException("Tenant database identity does not match its local binding");
			}
		}
	}

	private static void verifyPersistedWorkspace(Connection connection, UUID tenantId, UUID workspaceId,
			boolean requirePresent) throws SQLException {
		try (var statement = connection.prepareStatement("""
				 SELECT tenant_id, workspace_id
				 FROM nexa_platform.tenant_workspace_scope_anchor
				 WHERE tenant_id = ? AND workspace_id = ?
			 """)) {
			statement.setObject(1, tenantId);
			statement.setObject(2, workspaceId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					if (requirePresent) throw new IllegalStateException("Tenant Workspace scope anchor is missing");
					return;
				}
				if (!tenantId.equals(result.getObject("tenant_id", UUID.class))
						|| !workspaceId.equals(result.getObject("workspace_id", UUID.class))
						|| result.next()) {
					throw new IllegalStateException("Tenant Workspace scope anchor does not match its local binding");
				}
			}
		}
	}

	public static void verifyRuntimeRole(Connection connection) throws SQLException {
        verifyRuntimeRole(connection, false);
    }

    public static void verifyNeonRuntimeRole(Connection connection) throws SQLException {
        verifyNeonAdministratorPolicy(connection);
        verifyRuntimeRole(connection, true);
    }

    private static void verifyRuntimeRole(Connection connection, boolean neon) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT current_user,
				        runtime.rolcanlogin, runtime.rolsuper, runtime.rolcreatedb,
				        runtime.rolcreaterole, runtime.rolreplication, runtime.rolbypassrls,
				        migrator.rolcanlogin, migrator.rolsuper, migrator.rolcreatedb,
				        migrator.rolcreaterole, migrator.rolreplication, migrator.rolbypassrls,
				        (has_database_privilege('nexa_runtime', current_database(), 'CREATE')
				         OR has_database_privilege('nexa_runtime', current_database(), 'TEMPORARY')),
				        (EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace
				                   WHERE has_schema_privilege(runtime.oid, oid, 'CREATE'))),
				        has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'SELECT'),
				        (has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'INSERT')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'UPDATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'DELETE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'TRUNCATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'REFERENCES')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'TRIGGER')),
				        has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'SELECT'),
				        (has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'INSERT')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'UPDATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'DELETE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'TRUNCATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'REFERENCES')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'TRIGGER')),
		        has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT'),
		        (has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'INSERT')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRUNCATE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'REFERENCES')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRIGGER')),
				        EXISTS (SELECT 1 FROM pg_database
				                WHERE datname=current_database()
				                  AND datdba=(SELECT oid FROM pg_roles WHERE rolname='nexa_tenant_bootstrap_admin')),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=(SELECT oid FROM pg_roles WHERE rolname='nexa_runtime')
				                   OR roleid=(SELECT oid FROM pg_roles WHERE rolname='nexa_runtime')),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=(SELECT oid FROM pg_roles WHERE rolname='nexa_migrator')
				                   OR roleid=(SELECT oid FROM pg_roles WHERE rolname='nexa_migrator')),
				        runtime.rolinherit, migrator.rolinherit
				 FROM pg_roles runtime, pg_roles migrator
				 WHERE runtime.rolname='nexa_runtime' AND migrator.rolname='nexa_migrator'
			 """)) {
			if (!result.next()) throw new IllegalStateException("Tenant runtime and migrator roles are missing");
			if (!"nexa_runtime".equals(result.getString(1)) || !result.getBoolean(2)
					|| result.getBoolean(3) || result.getBoolean(4) || result.getBoolean(5)
					|| result.getBoolean(6) || result.getBoolean(7) || !result.getBoolean(8)
					|| result.getBoolean(9) || result.getBoolean(10) || result.getBoolean(11)
					|| result.getBoolean(12) || result.getBoolean(13) || result.getBoolean(14)
					|| result.getBoolean(15) || !result.getBoolean(16) || result.getBoolean(17)
				|| !result.getBoolean(18) || result.getBoolean(19) || !result.getBoolean(20)
				|| result.getBoolean(21) || (!neon && (!result.getBoolean(22) || result.getBoolean(23)
                || result.getBoolean(24))) || result.getBoolean(25) || result.getBoolean(26)) {
				throw new IllegalStateException("Tenant runtime or migrator role privileges are not least-privileged");
			}
		}
	}

	public static void verifyPolicySnapshotWriterRole(Connection connection) throws SQLException {
        verifyPolicySnapshotWriterRole(connection, false);
    }

    public static void verifyNeonPolicySnapshotWriterRole(Connection connection) throws SQLException {
        verifyNeonAdministratorPolicy(connection);
        verifyPolicySnapshotWriterRole(connection, true);
    }

    private static void verifyPolicySnapshotWriterRole(Connection connection, boolean neon) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT current_user,
				        writer.rolcanlogin, writer.rolsuper, writer.rolcreatedb,
				        writer.rolcreaterole, writer.rolreplication, writer.rolbypassrls,
				        (has_database_privilege('nexa_policy_snapshot_writer', current_database(), 'CREATE')
				         OR has_database_privilege('nexa_policy_snapshot_writer', current_database(), 'TEMPORARY')),
				        (EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace
				                   WHERE has_schema_privilege(writer.oid, oid, 'CREATE'))),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=writer.oid OR roleid=writer.oid),
				        has_schema_privilege('nexa_policy_snapshot_writer', 'nexa_platform', 'USAGE'),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.tenant_business_database_identity', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'INSERT')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'UPDATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'DELETE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'TRUNCATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'REFERENCES')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'TRIGGER')),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.tenant_workspace_scope_anchor', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'INSERT')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'UPDATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'DELETE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'TRUNCATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'REFERENCES')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'TRIGGER')),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'INSERT')
				         AND has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRUNCATE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'REFERENCES')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRIGGER')),
				        NOT EXISTS (
				            SELECT 1 FROM pg_class relation
				            JOIN pg_namespace namespace_row ON namespace_row.oid=relation.relnamespace
				            WHERE relation.relkind IN ('r','p','v','m','f')
				              AND namespace_row.nspname NOT IN ('pg_catalog','information_schema')
				              AND namespace_row.nspname || '.' || relation.relname NOT IN (
				                  'nexa_platform.tenant_business_database_identity',
				                  'nexa_platform.tenant_workspace_scope_anchor',
				                  'nexa_platform.purchase_request_expiry_policy_snapshot')
				              AND (has_table_privilege(writer.oid, relation.oid, 'SELECT')
				                   OR has_table_privilege(writer.oid, relation.oid, 'INSERT')
				                   OR has_table_privilege(writer.oid, relation.oid, 'UPDATE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'DELETE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'TRUNCATE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'REFERENCES')
				                   OR has_table_privilege(writer.oid, relation.oid, 'TRIGGER'))
				        )
				        AND NOT EXISTS (
				            SELECT 1 FROM pg_class sequence_row
				            JOIN pg_namespace namespace_row ON namespace_row.oid=sequence_row.relnamespace
				            WHERE sequence_row.relkind='S'
				              AND namespace_row.nspname NOT IN ('pg_catalog','information_schema')
				              AND (has_sequence_privilege(writer.oid, sequence_row.oid, 'USAGE')
				                   OR has_sequence_privilege(writer.oid, sequence_row.oid, 'SELECT')
				                   OR has_sequence_privilege(writer.oid, sequence_row.oid, 'UPDATE'))
				        )
				        AND NOT EXISTS (
				            SELECT 1 FROM pg_namespace namespace_row
				            WHERE namespace_row.nspname NOT IN
				                    ('pg_catalog','information_schema','public','nexa_platform')
				              AND has_schema_privilege(writer.oid, namespace_row.oid, 'USAGE')
				        )
				 FROM pg_roles writer WHERE writer.rolname='nexa_policy_snapshot_writer'
				 """)) {
			if (!result.next()) throw new IllegalStateException("Tenant policy-snapshot writer role is missing");
			if (!"nexa_policy_snapshot_writer".equals(result.getString(1)) || !result.getBoolean(2)
					|| result.getBoolean(3) || result.getBoolean(4) || result.getBoolean(5)
					|| result.getBoolean(6) || result.getBoolean(7) || result.getBoolean(8)
					|| result.getBoolean(9) || (!neon && result.getBoolean(10)) || !result.getBoolean(11)
					|| !result.getBoolean(12) || result.getBoolean(13) || !result.getBoolean(14)
					|| result.getBoolean(15) || !result.getBoolean(16) || !result.getBoolean(17)
					|| !result.getBoolean(18)) {
				throw new IllegalStateException("Tenant policy-snapshot writer role privileges are not least-privileged");
			}
		}
	}

	private static boolean exists(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getBoolean(1);
		}
	}


    private static void verifyNeonAdministratorPolicy(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()
                """)) {
            if (!rows.next() || !"neondb_owner".equals(rows.getString(1)) || rows.next()) {
                throw new IllegalStateException("Unexpected Neon Tenant database owner");
            }
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT parent.rolname, member.rolname, grantor.rolname,
                       membership.admin_option, membership.inherit_option, membership.set_option
                FROM pg_auth_members membership
                JOIN pg_roles parent ON parent.oid=membership.roleid
                JOIN pg_roles member ON member.oid=membership.member
                JOIN pg_roles grantor ON grantor.oid=membership.grantor
                WHERE parent.rolname IN ('nexa_runtime','nexa_migrator','nexa_policy_snapshot_writer')
                   OR member.rolname IN ('nexa_runtime','nexa_migrator','nexa_policy_snapshot_writer')
                """)) {
            while (rows.next()) {
                verifyNeonCreatorMembership(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6));
            }
        }
    }

    static void verifyNeonCreatorMembership(String role, String member, String grantor,
            boolean admin, boolean inherit, boolean set) {
        if (!java.util.Set.of("nexa_runtime", "nexa_migrator", "nexa_policy_snapshot_writer").contains(role)
                || !"neondb_owner".equals(member) || !"cloud_admin".equals(grantor)
                || !admin || inherit || set) {
            throw new IllegalStateException("Unexpected Neon Tenant role membership");
        }
    }
}
