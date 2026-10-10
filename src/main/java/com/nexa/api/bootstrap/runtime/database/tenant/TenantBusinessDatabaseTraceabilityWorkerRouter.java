package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.BaseStream;

/** Routes one bounded BC-11 callback through its least-privilege Tenant worker login. */
public final class TenantBusinessDatabaseTraceabilityWorkerRouter
        implements TenantBusinessTraceabilityWorkerRouter, AutoCloseable {
    private static final String WORKER_ROLE = "nexa_business_traceability_worker";
    private static final String CREDENTIAL_REFERENCE_PREFIX = "local-tenant-business-traceability-worker:";

    private final TenantBusinessDatabaseBindingRegistry bindings;
    private final TenantBusinessDatabasePoolRegistry pools;
    private final TenantBusinessDocumentWorkerScopeQuery scopes;

    public TenantBusinessDatabaseTraceabilityWorkerRouter(TenantBusinessDatabaseBindingRegistry bindings,
            TenantBusinessDatabaseDataSourceFactory workerDataSources,
            TenantBusinessDocumentWorkerScopeQuery scopes, int maximumCachedPools) {
        this.bindings = Objects.requireNonNull(bindings, "Central Tenant binding registry is required");
        this.pools = new TenantBusinessDatabasePoolRegistry(
                Objects.requireNonNull(workerDataSources, "BC-11 worker data-source factory is required"),
                maximumCachedPools);
        this.scopes = Objects.requireNonNull(scopes, "Central READY Workspace scope query is required");
    }

    @Override
    public <T> T inTransaction(UUID tenantIdValue, UUID workspaceIdValue,
            BiFunction<JdbcTemplate, PlatformTransactionManager, T> work) {
        TenantId tenantId = new TenantId(Objects.requireNonNull(tenantIdValue, "Tenant scope is required"));
        WorkspaceId workspaceId = new WorkspaceId(Objects.requireNonNull(workspaceIdValue, "Workspace scope is required"));
        Objects.requireNonNull(work, "Synchronous Tenant traceability work is required");
        TenantBusinessDatabaseRouter.ensureRouteCanStart();

        RlsRequestScope.Scope previousScope = RlsRequestScope.current();
        boolean previousCrossScope = RlsRequestScope.crossScopeWorkspaceScanEnabled();
        try {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
            RlsRequestScope.set(tenantId.value(), workspaceId.value());
            requireReadyWorkspace(tenantId, workspaceId);
            TenantBusinessDatabaseBinding centralBinding = readyBinding(tenantId);
            TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
            requirements.verifyRequiredSqlAssets();
            if (!requirements.schemaManifestDigest().equals(centralBinding.verifiedSchemaManifestSha256())) {
                pools.retireTenant(tenantId);
                throw new TenantBusinessDatabaseSchemaManifestMismatchException();
            }

            TenantBusinessDatabaseBinding workerBinding = new TenantBusinessDatabaseBinding(tenantId,
                    centralBinding.databaseIdentity(), CREDENTIAL_REFERENCE_PREFIX + tenantId.value(),
                    centralBinding.verifiedSchemaManifestSha256());
            T result;
            try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(workerBinding)) {
                DataSource tenantDataSource = RlsScopedDataSource.forVerifiedSystemTenant(
                        lease.dataSource(workspaceId), tenantId, workspaceId);
                JdbcTemplate jdbc = new JdbcTemplate(tenantDataSource);
                DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(tenantDataSource);
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                TenantBusinessDatabaseRouter.enterRoutedTransaction(workerBinding);
                try {
                    result = transaction.execute(status -> {
                        verifyWorkerRole(jdbc);
                        requirePhysicalAnchor(jdbc, tenantId, workspaceId);
                        T value = work.apply(jdbc, transactionManager);
                        rejectEscapedResource(value);
                        return value;
                    });
                    requirePhysicalAnchor(jdbc, tenantId, workspaceId);
                } finally {
                    TenantBusinessDatabaseRouter.exitRoutedTransaction();
                }
            }

            TenantBusinessDatabaseBinding currentBinding = readyBinding(tenantId);
            if (!sameReadyBinding(centralBinding, currentBinding)
                    || !scopes.isReadyWorkspace(tenantId.value(), workspaceId.value())) {
                pools.retireTenant(tenantId);
                throw new TenantBusinessDatabaseUnavailableException(
                        "Tenant traceability work completed while its READY binding or Workspace anchor changed");
            }
            return result;
        } catch (TenantBusinessDatabaseUnavailableException exception) {
            pools.retireTenant(tenantId);
            throw exception;
        } finally {
            RlsRequestScope.clear();
            if (previousScope != null) RlsRequestScope.set(previousScope.tenantId(), previousScope.workspaceId());
            if (previousCrossScope) RlsRequestScope.enableCrossScopeWorkspaceScan();
        }
    }

    private void requireReadyWorkspace(TenantId tenantId, WorkspaceId workspaceId) {
        if (!scopes.isReadyWorkspace(tenantId.value(), workspaceId.value())) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant traceability work requires a READY Tenant database and Workspace anchor");
        }
    }

    private TenantBusinessDatabaseBinding readyBinding(TenantId tenantId) {
        return bindings.findReadyBinding(tenantId).filter(binding -> binding.tenantId().equals(tenantId))
                .orElseThrow(() -> new TenantBusinessDatabaseUnavailableException(
                        "No READY Tenant database is provisioned for traceability work"));
    }

    private static boolean sameReadyBinding(TenantBusinessDatabaseBinding expected,
            TenantBusinessDatabaseBinding actual) {
        return expected.tenantId().equals(actual.tenantId())
                && expected.databaseIdentity().equals(actual.databaseIdentity())
                && Objects.equals(expected.verifiedSchemaManifestSha256(), actual.verifiedSchemaManifestSha256());
    }

    private static void requirePhysicalAnchor(JdbcTemplate jdbc, TenantId tenantId, WorkspaceId workspaceId) {
        Boolean exists = jdbc.queryForObject("select exists(select 1 from nexa_platform.tenant_workspace_scope_anchor "
                        + "where tenant_id=? and workspace_id=?)", Boolean.class,
                tenantId.value(), workspaceId.value());
        if (!Boolean.TRUE.equals(exists)) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant traceability work requires the physical Workspace scope anchor");
        }
    }

    private static void verifyWorkerRole(JdbcTemplate jdbc) {
        String role = jdbc.queryForObject("select current_user", String.class);
        Boolean safeRole = jdbc.queryForObject("select rolcanlogin and not rolsuper and not rolcreatedb "
                + "and not rolcreaterole and not rolreplication and not rolbypassrls "
                + "and not exists (select 1 from pg_auth_members where member=pg_roles.oid or roleid=pg_roles.oid) "
                + "and not exists (select 1 from pg_database where datdba=pg_roles.oid) "
                + "and not exists (select 1 from pg_namespace where nspowner=pg_roles.oid) "
                + "and not exists (select 1 from pg_class where relowner=pg_roles.oid) "
                + "and not exists (select 1 from pg_proc where proowner=pg_roles.oid) "
                + "and not exists (select 1 from pg_namespace where has_schema_privilege(pg_roles.oid, oid, 'CREATE')) "
                + "from pg_roles where rolname=current_user", Boolean.class);
        if (!WORKER_ROLE.equals(role) || !Boolean.TRUE.equals(safeRole)) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant traceability worker requires its isolated least-privilege role");
        }
    }

    private static void rejectEscapedResource(Object value) {
        if (value instanceof JdbcTemplate || value instanceof PlatformTransactionManager || value instanceof DataSource
                || value instanceof Connection || value instanceof Statement || value instanceof ResultSet
                || value instanceof Future<?> || value instanceof CompletionStage<?> || value instanceof BaseStream<?, ?>) {
            throw new IllegalStateException(
                    "Tenant traceability work must not return JDBC resources or asynchronous results");
        }
    }

    @Override
    public void close() { pools.close(); }
}
