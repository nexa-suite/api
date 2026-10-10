package com.nexa.api.creditreceivables.tenantdatabase;

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
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-07 owner ports to the exact Tenant JDBC session supplied by the router callback. */
public interface TenantCreditAccountAdapterFactory {
    ReceivablePaymentAccess bindReceivablePaymentAccessTo(JdbcTemplate tenantJdbc);

    ConfigurationBindings bindConfigurationTo(JdbcTemplate tenantJdbc,
                                                BusinessTraceabilityCommands tenantTraceability);

    Bindings bindTo(JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts,
                    ReceivablePaymentAccess tenantReceivablePaymentAccess,
                    BusinessTraceabilityCommands tenantTraceability, CanonicalOutboxPort tenantCanonicalOutbox,
                    FinancialAdjustmentSource tenantFinancialAdjustmentSource);

    record ConfigurationBindings(CreditAccountConfigurationQuery query,
                                 CreditAccountConfigurationCommands commands) {
        public ConfigurationBindings {
            if (query == null || commands == null) {
                throw new IllegalArgumentException("Tenant credit configuration bindings are incomplete");
            }
        }
    }

    record Bindings(CustomerAccountQuery customerAccounts, CreditExposureQuery creditExposure,
                    CreditReservationCommands creditReservations,
                    ReceivableCommands receivables, ReceivablePaymentAccess receivablePaymentAccess,
                    ReceivableApplicationCommands receivableApplications, CreditPaymentCommands creditPayments,
                    FinancialAdjustmentCommands financialAdjustments,
                    CreditAccountConfigurationQuery creditAccountConfigurations,
                    CreditAccountConfigurationCommands creditAccountConfigurationCommands) {
        public Bindings {
            if (customerAccounts == null || creditExposure == null || creditReservations == null || receivables == null
                    || receivablePaymentAccess == null || receivableApplications == null || creditPayments == null
                    || financialAdjustments == null
                    || creditAccountConfigurations == null || creditAccountConfigurationCommands == null) {
                throw new IllegalArgumentException("Tenant Credit & Receivables bindings are incomplete");
            }
        }
    }
}
