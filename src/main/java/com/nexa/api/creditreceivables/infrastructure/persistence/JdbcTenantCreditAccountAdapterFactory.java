package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationCommands;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditPaymentCommands;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableApplicationCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.LegacyCustomerCreditInitializationQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantLegacyCustomerCreditInitializationQueryFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Binds every BC-07 owner adapter to the Tenant router's current transaction session. */
@Component
@Profile("!test")
public final class JdbcTenantCreditAccountAdapterFactory implements TenantCreditAccountAdapterFactory {
    private final TenantLegacyCustomerCreditInitializationQueryFactory legacyCreditQueries;
    private final ObjectMapper mapper;

    public JdbcTenantCreditAccountAdapterFactory(
            TenantLegacyCustomerCreditInitializationQueryFactory legacyCreditQueries, ObjectMapper mapper) {
        this.legacyCreditQueries = Objects.requireNonNull(legacyCreditQueries,
                "Tenant legacy credit query factory is required");
        this.mapper = Objects.requireNonNull(mapper, "Object mapper is required");
    }

    @Override
    public ReceivablePaymentAccess bindReceivablePaymentAccessTo(JdbcTemplate tenantJdbc) {
        return new JdbcReceivablePaymentAccess(requireTenantJdbc(tenantJdbc));
    }

    @Override
    public ConfigurationBindings bindConfigurationTo(JdbcTemplate tenantJdbc,
            BusinessTraceabilityCommands tenantTraceability) {
        JdbcTemplate jdbc = requireTenantJdbc(tenantJdbc);
        CreditAccountConfigurationQuery query = new JdbcCreditAccountConfigurationAdapter(jdbc,
                tenantTraceability, mapper, requireLegacyCreditQuery(jdbc));
        return new ConfigurationBindings(query, (CreditAccountConfigurationCommands) query);
    }

    @Override
    public Bindings bindTo(JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts,
            ReceivablePaymentAccess tenantReceivablePaymentAccess,
            BusinessTraceabilityCommands tenantTraceability, CanonicalOutboxPort tenantCanonicalOutbox,
            FinancialAdjustmentSource tenantFinancialAdjustmentSource) {
        JdbcTemplate jdbc = requireTenantJdbc(tenantJdbc);
        CustomerAccountQuery accounts = Objects.requireNonNull(tenantCustomerAccounts,
                "Tenant Customer Account query is required");
        ReceivablePaymentAccess receivables = Objects.requireNonNull(tenantReceivablePaymentAccess,
                "Tenant Receivable access is required");
        BusinessTraceabilityCommands traceability = Objects.requireNonNull(tenantTraceability,
                "Tenant traceability commands are required");
        CanonicalOutboxPort outbox = Objects.requireNonNull(tenantCanonicalOutbox,
                "Tenant canonical outbox is required");
        FinancialAdjustmentSource adjustmentSource = Objects.requireNonNull(tenantFinancialAdjustmentSource,
                "Tenant financial adjustment source is required");

        // A Tenant commitment must never lazily copy credit settings from a central ClientAccount.
        // ConfigureCredit creates the local account explicitly; a missing local row therefore fails closed.
        LegacyCustomerCreditInitializationQuery noLegacyFallback =
                (tenant, workspace, account, currency) -> Optional.empty();
        JdbcCreditBoundary creditBoundary = new JdbcCreditBoundary(jdbc, noLegacyFallback);
        ConfigurationBindings configuration = bindConfigurationTo(jdbc, traceability);
        ReceivableCommands receivableCommands = new JdbcReceivableCommands(jdbc, outbox);
        ReceivableApplicationCommands receivableApplications = new JdbcReceivableApplicationAdapter(
                jdbc, traceability, outbox);
        CreditPaymentCommands creditPayments = new JdbcCreditPaymentAdapter(jdbc, traceability);
        FinancialAdjustmentCommands financialAdjustments = new JdbcFinancialAdjustmentAdapter(
                jdbc, traceability, adjustmentSource);
        return new Bindings(accounts, creditBoundary, creditBoundary, receivableCommands, receivables,
                receivableApplications, creditPayments, financialAdjustments, configuration.query(),
                configuration.commands());
    }

    private LegacyCustomerCreditInitializationQuery requireLegacyCreditQuery(JdbcTemplate jdbc) {
        return Objects.requireNonNull(legacyCreditQueries.bindTo(jdbc),
                "Tenant legacy credit query factory returned no query");
    }

    private static JdbcTemplate requireTenantJdbc(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
    }
}
