package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** Routes only authenticated Stripe recharge work through the dedicated Tenant-local worker credential. */
public final class TenantBusinessDatabaseWalletRechargeCallbackRouter implements AutoCloseable {
    private final TenantBusinessDatabasePoolRegistry pools;

    public TenantBusinessDatabaseWalletRechargeCallbackRouter(
            TenantBusinessDatabaseDataSourceFactory dataSourceFactory, int maximumCachedPools) {
        this.pools = new TenantBusinessDatabasePoolRegistry(
                Objects.requireNonNull(dataSourceFactory, "Wallet recharge worker pool factory is required"),
                maximumCachedPools);
    }

    public <T> T inTransaction(TenantBusinessDatabaseBinding callbackBinding, WorkspaceId workspaceId,
            TenantBusinessDatabaseWork<T> work) {
        Objects.requireNonNull(callbackBinding, "Central verified Tenant callback binding is required");
        Objects.requireNonNull(work, "Wallet recharge callback work is required");
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        if (callbackBinding.verifiedSchemaManifestSha256() == null
                || !requirements.schemaManifestDigest().equals(callbackBinding.verifiedSchemaManifestSha256())) {
            throw new TenantBusinessDatabaseSchemaManifestMismatchException();
        }
        TenantBusinessDatabaseRouter.ensureRouteCanStart();
        RlsRequestScope.Scope priorScope = RlsRequestScope.current();
        boolean priorCrossScopeScan = RlsRequestScope.crossScopeWorkspaceScanEnabled();
        try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(callbackBinding)) {
            DataSource tenantDataSource = RlsScopedDataSource.forVerifiedSystemTenant(
                    lease.dataSource(workspaceId), callbackBinding.tenantId(), workspaceId);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(tenantDataSource));
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            RlsRequestScope.set(callbackBinding.tenantId().value(), workspaceId.value());
            TenantBusinessDatabaseRouter.enterRoutedTransaction(callbackBinding);
            try {
                return transaction.execute(status -> {
                    T result = work.execute(new JdbcTemplate(tenantDataSource));
                    rejectEscapedResource(result);
                    return result;
                });
            } finally {
                TenantBusinessDatabaseRouter.exitRoutedTransaction();
            }
        } finally {
            RlsRequestScope.clear();
            if (priorScope != null) RlsRequestScope.set(priorScope.tenantId(), priorScope.workspaceId());
            if (priorCrossScopeScan) RlsRequestScope.enableCrossScopeWorkspaceScan();
        }
    }

    /** Proves callback credential and exact BC-08 table permissions before any PSP intent is created. */
    public void verifyConfigured(TenantBusinessDatabaseBinding callbackBinding, WorkspaceId workspaceId) {
        inTransaction(callbackBinding, workspaceId, jdbc -> {
            String role = jdbc.queryForObject("select current_user", String.class);
            if (!"nexa_wallet_recharge_worker".equals(role)) {
                throw unavailable("Tenant wallet recharge callback requires its dedicated worker role");
            }
            Boolean unsafeRole = jdbc.queryForObject(
                    "select rolsuper or rolbypassrls from pg_roles where rolname=current_user", Boolean.class);
            if (!Boolean.FALSE.equals(unsafeRole)) {
                throw unavailable("Tenant wallet recharge worker role is not least-privilege");
            }
            requireTrue(jdbc.queryForObject("select has_schema_privilege(current_user,'payments','USAGE')",
                    Boolean.class));
            requireTable(jdbc, "payments.buyer_wallet_recharge", true, true);
            requireColumn(jdbc, "payments.buyer_wallet_recharge", "provider_payment_intent_id", "UPDATE");
            requireColumn(jdbc, "payments.buyer_wallet_recharge", "status", "UPDATE");
            requireColumn(jdbc, "payments.buyer_wallet_recharge", "updated_at", "UPDATE");
            requireColumn(jdbc, "payments.buyer_wallet_recharge", "completed_at", "UPDATE");
            requireTable(jdbc, "payments.buyer_wallet_recharge_processed_event", true, true);
            requireTable(jdbc, "payments.buyer_wallet_account", true, false);
            requireColumn(jdbc, "payments.buyer_wallet_account", "id", "INSERT");
            requireColumn(jdbc, "payments.buyer_wallet_account", "tenant_id", "INSERT");
            requireColumn(jdbc, "payments.buyer_wallet_account", "workspace_id", "INSERT");
            requireColumn(jdbc, "payments.buyer_wallet_account", "buyer_identity_id", "INSERT");
            requireColumn(jdbc, "payments.buyer_wallet_account", "currency", "INSERT");
            requireColumn(jdbc, "payments.buyer_wallet_account", "posted_balance", "UPDATE");
            requireColumn(jdbc, "payments.buyer_wallet_account", "version", "UPDATE");
            requireColumn(jdbc, "payments.buyer_wallet_account", "updated_at", "UPDATE");
            requireTable(jdbc, "payments.buyer_wallet_ledger_entry", true, true);
            requireRls(jdbc, "payments.buyer_wallet_recharge");
            requireRls(jdbc, "payments.buyer_wallet_recharge_processed_event");
            requireRls(jdbc, "payments.buyer_wallet_account");
            requireRls(jdbc, "payments.buyer_wallet_ledger_entry");
            return null;
        });
    }

    private static void requireTable(JdbcTemplate jdbc, String table, boolean select, boolean insert) {
        Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
        if (!Boolean.TRUE.equals(exists)) throw unavailable("Required Tenant wallet recharge table is missing");
        Boolean privileges = jdbc.queryForObject("select (? = false or has_table_privilege(current_user, ?, 'SELECT')) "
                        + "and (? = false or has_table_privilege(current_user, ?, 'INSERT'))",
                Boolean.class, !select, table, !insert, table);
        requireTrue(privileges);
    }

    private static void requireColumn(JdbcTemplate jdbc, String table, String column, String privilege) {
        Boolean allowed = jdbc.queryForObject("select has_column_privilege(current_user, ?, ?, ?)",
                Boolean.class, table, column, privilege);
        requireTrue(allowed);
    }

    private static void requireRls(JdbcTemplate jdbc, String table) {
        Boolean enabled = jdbc.query("select relrowsecurity and relforcerowsecurity from pg_class "
                        + "where oid=to_regclass(?)", (rs, row) -> rs.getBoolean(1), table)
                .stream().findFirst().orElse(false);
        requireTrue(enabled);
    }

    private static void requireTrue(Boolean value) {
        if (!Boolean.TRUE.equals(value)) throw unavailable(
                "Tenant wallet recharge worker privileges or RLS policy are incomplete");
    }

    private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
        return new TenantBusinessDatabaseUnavailableException(message);
    }

    private static void rejectEscapedResource(Object result) {
        if (result instanceof JdbcTemplate || result instanceof DataSource || result instanceof java.sql.Connection
                || result instanceof java.sql.Statement || result instanceof java.sql.ResultSet
                || result instanceof java.util.concurrent.Future<?> || result instanceof java.util.concurrent.CompletionStage<?>
                || result instanceof java.util.stream.BaseStream<?, ?>) {
            throw new IllegalStateException("Tenant wallet recharge work must not return JDBC resources or asynchronous results");
        }
    }

    @Override
    public void close() { pools.close(); }
}
