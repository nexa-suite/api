package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.exception.CreditAccountConfigurationUnavailableException;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationUseCase;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationAuthorization;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationCommands;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountDirectoryQueryFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Executes BC-07 setup against only the verified Tenant callback transaction. */
public final class TenantBoundCreditAccountConfigurationUseCase implements CreditAccountConfigurationUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountDirectoryQueryFactory accountDirectory;
    private final TenantCreditAccountAdapterFactory creditAdapters;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;

    public TenantBoundCreditAccountConfigurationUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountDirectoryQueryFactory accountDirectory,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.accountDirectory = Objects.requireNonNull(accountDirectory,
                "Tenant Customer Account directory factory is required");
        this.creditAdapters = Objects.requireNonNull(creditAdapters,
                "Tenant Credit & Receivables adapter factory is required");
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings,
                "Tenant traceability bindings factory is required");
    }

    @Override
    public CustomerAccountDirectoryQuery.Page candidates(CurrentAccessContext context, String search,
            String status, int page, int size) {
        requireScope(context);
        try {
            return router.inTransaction(context, jdbc -> directory(jdbc).list(
                    context.tenantId().value(), context.workspaceId().value(), search, status, page, size));
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException exception) {
            throw new CreditAccountConfigurationUnavailableException(exception);
        }
    }

    @Override
    public CreditAccountConfigurationQuery.Snapshot find(CurrentAccessContext context, UUID customerAccountId,
            String currency) {
        requireScope(context);
        Objects.requireNonNull(customerAccountId, "Client Account id is required");
        try {
            return router.inTransaction(context, jdbc -> {
                requireCustomerAccount(context, customerAccountId, jdbc);
                ConfigurationBindings bindings = configurationBindings(jdbc);
                return bindings.query().find(context.tenantId().value(), context.workspaceId().value(),
                        customerAccountId, currency);
            });
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException exception) {
            throw new CreditAccountConfigurationUnavailableException(exception);
        }
    }

    @Override
    public CreditAccountConfigurationQuery.Snapshot configure(CurrentAccessContext context, UUID customerAccountId,
            String currency, BigDecimal creditLimit, boolean active, boolean createIfAbsent, Long expectedVersion,
            String idempotencyKey, String requestHash, Instant now) {
        requireScope(context);
        Objects.requireNonNull(customerAccountId, "Client Account id is required");
        try {
            return router.inTransaction(context, jdbc -> {
                requireCustomerAccount(context, customerAccountId, jdbc);
                ConfigurationBindings bindings = configurationBindings(jdbc);
                CreditAccountConfigurationCommands.Request command = new CreditAccountConfigurationCommands.Request(
                        context.tenantId().value(), context.workspaceId().value(), customerAccountId,
                        context.membershipId().value(), currency, creditLimit, active, createIfAbsent,
                        expectedVersion, idempotencyKey, requestHash, now);
                return bindings.commands().configure(command);
            });
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException exception) {
            throw new CreditAccountConfigurationUnavailableException(exception);
        }
    }

    private ConfigurationBindings configurationBindings(JdbcTemplate jdbc) {
        TenantBusinessTraceabilityBindingsFactory.Bindings traceability = traceabilityBindings.bindTo(jdbc);
        TenantCreditAccountAdapterFactory.ConfigurationBindings credit = creditAdapters.bindConfigurationTo(
                jdbc, traceability.commands());
        return new ConfigurationBindings(credit.query(), credit.commands());
    }

    private CustomerAccountDirectoryQuery directory(JdbcTemplate jdbc) {
        return Objects.requireNonNull(accountDirectory.bindTo(jdbc),
                "Tenant Customer Account directory factory returned no query");
    }

    private void requireCustomerAccount(CurrentAccessContext context, UUID customerAccountId, JdbcTemplate jdbc) {
        if (directory(jdbc).find(context.tenantId().value(), context.workspaceId().value(), customerAccountId).isEmpty()) {
            throw new CreditReceivableOperationException("CLIENT_ACCOUNT_NOT_FOUND");
        }
    }

    private static void requireScope(CurrentAccessContext context) {
        CreditAccountConfigurationAuthorization.require(context);
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.equals(current.tenantId()) || !workspaceId.equals(current.workspaceId())) {
            throw new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation(
                    "Credit configuration requires the matching verified Tenant and Workspace scope");
        }
    }

    private record ConfigurationBindings(CreditAccountConfigurationQuery query,
                                         CreditAccountConfigurationCommands commands) { }
}
