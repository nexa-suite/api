package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.BaseStream;

/** Runs signed provider callbacks on one READY Tenant using the isolated Payments worker credential. */
public final class TenantBusinessDatabasePaymentCallbackRouter implements AutoCloseable {
    private static final String WORKER_ROLE = "nexa_payments_worker";

    private final JdbcPaymentProviderRouteRegistry routes;
    private final TenantBusinessDatabasePoolRegistry pools;

    public TenantBusinessDatabasePaymentCallbackRouter(JdbcPaymentProviderRouteRegistry routes,
            TenantBusinessDatabaseDataSourceFactory workerDataSources, int maximumCachedPools) {
        this.routes = Objects.requireNonNull(routes, "Central payment route registry is required");
        this.pools = new TenantBusinessDatabasePoolRegistry(Objects.requireNonNull(workerDataSources),
                maximumCachedPools);
    }

    public <T> T inTransaction(JdbcPaymentProviderRouteRegistry.Route requestedRoute,
            BiFunction<JdbcTemplate, PlatformTransactionManager, T> work) {
        Objects.requireNonNull(requestedRoute, "Opaque verified Tenant payment route is required");
        Objects.requireNonNull(work, "Synchronous Tenant callback work is required");
        TenantBusinessDatabaseRouter.ensureRouteCanStart();
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        if (!requirements.schemaManifestDigest().equals(requestedRoute.verifiedSchemaManifestSha256())) {
            throw new TenantBusinessDatabaseSchemaManifestMismatchException();
        }
        JdbcPaymentProviderRouteRegistry.Route before = requireReadyRoute(requestedRoute.routeId());
        requireSameRoute(requestedRoute, before);
        if (!"AWAITING_PAYMENT".equals(before.status()) && !terminal(before.status())) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant payment provider route is not ready for callback processing");
        }

        TenantId tenantId = before.tenantId();
        WorkspaceId workspaceId = before.workspaceId();
        TenantBusinessDatabaseBinding workerBinding = new TenantBusinessDatabaseBinding(tenantId,
                before.databaseIdentity(), before.callbackCredentialSecretReference(),
                before.verifiedSchemaManifestSha256());
        RlsRequestScope.Scope previousScope = RlsRequestScope.current();
        boolean previousCrossScope = RlsRequestScope.crossScopeWorkspaceScanEnabled();
        T result;
        try {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
            RlsRequestScope.set(tenantId.value(), workspaceId.value());
            try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(workerBinding)) {
                DataSource tenantDataSource = RlsScopedDataSource.forVerifiedSystemTenant(
                        lease.dataSource(workspaceId), tenantId, workspaceId);
                JdbcTemplate jdbc = new JdbcTemplate(tenantDataSource);
                DataSourceTransactionManager delegate = new DataSourceTransactionManager(tenantDataSource);
                PlatformTransactionManager transactionManager = delegate;
                TransactionTemplate transaction = new TransactionTemplate(delegate);
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
                    // The transaction has committed and released its connection; re-open through the
                    // identity-checking source to prove the physical identity and anchor still exist.
                    requirePhysicalAnchor(jdbc, tenantId, workspaceId);
                } finally {
                    TenantBusinessDatabaseRouter.exitRoutedTransaction();
                }
            }
            JdbcPaymentProviderRouteRegistry.Route after = requireReadyRoute(requestedRoute.routeId());
            requireSameRoute(before, after);
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

    private JdbcPaymentProviderRouteRegistry.Route requireReadyRoute(java.util.UUID routeId) {
        JdbcPaymentProviderRouteRegistry.Route route = routes.findByRouteId(routeId);
        if (route == null) throw new TenantBusinessDatabaseUnavailableException(
                "Tenant payment route, READY binding, or Workspace anchor is unavailable");
        return route;
    }

    private static void requirePhysicalAnchor(JdbcTemplate jdbc, TenantId tenantId, WorkspaceId workspaceId) {
        Boolean ready = jdbc.queryForObject("select exists(select 1 from nexa_platform.tenant_workspace_scope_anchor "
                        + "where tenant_id=? and workspace_id=?)", Boolean.class,
                tenantId.value(), workspaceId.value());
        if (!Boolean.TRUE.equals(ready)) throw new TenantBusinessDatabaseUnavailableException(
                "Tenant payment callback requires its physical Workspace scope anchor");
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
                    "Tenant callback requires the isolated least-privilege Payments worker role");
        }
    }

    private static void requireSameRoute(JdbcPaymentProviderRouteRegistry.Route expected,
            JdbcPaymentProviderRouteRegistry.Route actual) {
        if (!expected.routeId().equals(actual.routeId()) || !expected.tenantId().equals(actual.tenantId())
                || !expected.workspaceId().equals(actual.workspaceId())
                || !expected.databaseIdentity().equals(actual.databaseIdentity())
                || !expected.paymentId().equals(actual.paymentId())
                || !expected.providerCode().equals(actual.providerCode())
                || expected.amountMinor() != actual.amountMinor()
                || !expected.currency().equals(actual.currency())
                || !Objects.equals(expected.providerPaymentIntentId(), actual.providerPaymentIntentId())
                || !expected.callbackCredentialSecretReference().equals(actual.callbackCredentialSecretReference())
                || !expected.verifiedSchemaManifestSha256().equals(actual.verifiedSchemaManifestSha256())) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant payment route changed while processing its callback");
        }
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "REJECTED".equals(status);
    }

    private static void rejectEscapedResource(Object value) {
        if (value instanceof JdbcTemplate || value instanceof PlatformTransactionManager || value instanceof DataSource
                || value instanceof Connection || value instanceof Statement || value instanceof ResultSet
                || value instanceof Future<?> || value instanceof CompletionStage<?> || value instanceof BaseStream<?, ?>) {
            throw new IllegalStateException("Tenant payment callback work must not return JDBC resources or asynchronous results");
        }
    }

    @Override public void close() { pools.close(); }
}
