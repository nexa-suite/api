package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceScopeReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Read-only Workspace metadata scan using the dedicated central scope-reader login. */
@Repository
@Profile("!test")
public class JdbcWorkspaceScopeReader implements WorkspaceScopeReader {
    private static final String LOGIN_ROLE = "nexa_workspace_scope_reader_login";
    private static final String PRIVILEGE_ROLE = "nexa_workspace_scope_reader";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcWorkspaceScopeReader(
            @Qualifier("workspaceScopeReaderDataSourceProvider") Supplier<DataSource> scopedDataSourceProvider) {
        DataSource scopedDataSource = scopedDataSourceProvider.get();
        this.jdbc = new JdbcTemplate(scopedDataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(scopedDataSource));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setReadOnly(true);
    }

    @Override
    public List<WorkspaceDirectory.Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit) {
        return scan(tenantId, workspaceId, limit, ScanMode.ALL);
    }

    @Override
    public List<WorkspaceDirectory.Scope> scanActiveWorkspacesAfter(UUID tenantId, UUID workspaceId, int limit) {
        return scan(tenantId, workspaceId, limit, ScanMode.ACTIVE_WORKSPACE);
    }

    @Override
    public List<WorkspaceDirectory.Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit) {
        return scan(tenantId, workspaceId, limit, ScanMode.ACTIVE_TENANT_AND_WORKSPACE);
    }

    private List<WorkspaceDirectory.Scope> scan(UUID tenantId, UUID workspaceId, int limit, ScanMode mode) {
        requireScanScope(tenantId, workspaceId, limit);
        List<WorkspaceDirectory.Scope> result = transaction.execute(status -> {
            jdbc.execute("SET LOCAL ROLE " + PRIVILEGE_ROLE);
            verifyScopeReaderRole();

            String fromAndPredicate = switch (mode) {
                case ALL -> "FROM tenant_management.workspace w WHERE true";
                case ACTIVE_WORKSPACE -> "FROM tenant_management.workspace w WHERE w.status='ACTIVE'";
                case ACTIVE_TENANT_AND_WORKSPACE -> "FROM tenant_management.workspace w "
                        + "JOIN tenant_management.tenant t ON t.id=w.tenant_id "
                        + "WHERE t.status='ACTIVE' AND w.status='ACTIVE'";
            };
            String cursor = tenantId == null ? "" : " AND (w.tenant_id,w.id) > (?,?)";
            String sql = "SELECT w.tenant_id,w.id " + fromAndPredicate + cursor
                    + " ORDER BY w.tenant_id,w.id LIMIT ?";
            if (tenantId == null) {
                return jdbc.query(sql,
                        (rs, row) -> new WorkspaceDirectory.Scope(rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class)), limit);
            }
            return jdbc.query(sql,
                    (rs, row) -> new WorkspaceDirectory.Scope(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class)), tenantId, workspaceId, limit);
        });
        return result == null ? List.of() : List.copyOf(result);
    }

    private enum ScanMode { ALL, ACTIVE_WORKSPACE, ACTIVE_TENANT_AND_WORKSPACE }

    private static void requireScanScope(UUID tenantId, UUID workspaceId, int limit) {
        if (!RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
            throw new IllegalStateException("Explicit worker workspace enumeration scope is required");
        }
        if ((tenantId == null) != (workspaceId == null)) {
            throw new IllegalArgumentException("Both workspace pagination cursor identifiers must be supplied together");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Workspace scope page size must be between 1 and 100");
        }
    }

    private void verifyScopeReaderRole() {
        Boolean valid = jdbc.queryForObject("""
                SELECT session_user = 'nexa_workspace_scope_reader_login'
                   AND current_user = 'nexa_workspace_scope_reader'
                   AND current_setting('app.cross_scope_workspace_scan', true) = 'true'
                   AND EXISTS (
                       SELECT 1 FROM pg_roles
                        WHERE rolname = current_user
                          AND NOT rolcanlogin AND NOT rolinherit AND NOT rolsuper
                          AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication
                          AND NOT rolbypassrls
                   )
                   AND EXISTS (
                       SELECT 1 FROM pg_roles
                        WHERE rolname = session_user
                          AND rolcanlogin AND NOT rolinherit AND NOT rolsuper
                          AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication
                          AND NOT rolbypassrls
                   )
                   AND (SELECT count(*) = 1
                          FROM pg_auth_members
                         WHERE member = (SELECT oid FROM pg_roles WHERE rolname = session_user)
                           AND roleid = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                           AND NOT inherit_option AND set_option AND NOT admin_option)
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_auth_members
                        WHERE member = (SELECT oid FROM pg_roles WHERE rolname = session_user)
                          AND roleid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_auth_members
                        WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_database
                        WHERE datdba = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_namespace
                        WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                           OR has_schema_privilege(current_user, oid, 'CREATE')
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_class
                        WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_proc
                        WHERE proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_proc function_row
                       JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
                        WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND has_schema_privilege(current_user, schema_row.oid, 'USAGE')
                          AND has_function_privilege(current_user, function_row.oid, 'EXECUTE')
                          AND NOT (
                              schema_row.nspname = 'public'
                              AND NOT function_row.prosecdef
                              AND EXISTS (
                                  SELECT 1 FROM pg_depend extension_dependency
                                  JOIN pg_extension extension_row
                                    ON extension_row.oid = extension_dependency.refobjid
                                   WHERE extension_dependency.classid = 'pg_proc'::regclass
                                     AND extension_dependency.objid = function_row.oid
                                     AND extension_dependency.refclassid = 'pg_extension'::regclass
                                     AND extension_dependency.deptype = 'e'
                                     AND extension_row.extname = 'btree_gist'
                              )
                          )
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_proc function_row
                       JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
                        WHERE schema_row.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND has_schema_privilege(session_user, schema_row.oid, 'USAGE')
                          AND has_function_privilege(session_user, function_row.oid, 'EXECUTE')
                          AND NOT (
                              schema_row.nspname = 'public'
                              AND NOT function_row.prosecdef
                              AND EXISTS (
                                  SELECT 1 FROM pg_depend extension_dependency
                                  JOIN pg_extension extension_row
                                    ON extension_row.oid = extension_dependency.refobjid
                                   WHERE extension_dependency.classid = 'pg_proc'::regclass
                                     AND extension_dependency.objid = function_row.oid
                                     AND extension_dependency.refclassid = 'pg_extension'::regclass
                                     AND extension_dependency.deptype = 'e'
                                     AND extension_row.extname = 'btree_gist'
                              )
                          )
                   )
                """, Boolean.class);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalStateException("Dedicated Workspace scope-reader must not execute application functions except approved btree_gist operator support");
        }
    }

}
