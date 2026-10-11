package com.nexa.api.support;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.infrastructure.persistence.JdbcTenantCreditAccountAdapterFactory;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tenant-bound financial-adjustment failure seam for delivery rollback integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TenantFinancialAdjustmentFixtureConfiguration {
    @Bean
    TenantFinancialAdjustmentFixture tenantFinancialAdjustmentFixture() {
        return new TenantFinancialAdjustmentFixture();
    }

    @Bean
    @Primary
    TenantCreditAccountAdapterFactory fixtureCreditAccountAdapterFactory(
            JdbcTenantCreditAccountAdapterFactory delegate, TenantFinancialAdjustmentFixture fixture) {
        return new TenantCreditAccountAdapterFactory() {
            @Override
            public ReceivablePaymentAccess bindReceivablePaymentAccessTo(JdbcTemplate tenantJdbc) {
                return delegate.bindReceivablePaymentAccessTo(tenantJdbc);
            }

            @Override
            public ConfigurationBindings bindConfigurationTo(JdbcTemplate tenantJdbc,
                    BusinessTraceabilityCommands tenantTraceability) {
                return delegate.bindConfigurationTo(tenantJdbc, tenantTraceability);
            }

            @Override
            public Bindings bindTo(JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts,
                    ReceivablePaymentAccess tenantReceivablePaymentAccess,
                    BusinessTraceabilityCommands tenantTraceability, CanonicalOutboxPort tenantCanonicalOutbox,
                    FinancialAdjustmentSource tenantFinancialAdjustmentSource) {
                Bindings bindings = delegate.bindTo(tenantJdbc, tenantCustomerAccounts, tenantReceivablePaymentAccess,
                        tenantTraceability, tenantCanonicalOutbox, tenantFinancialAdjustmentSource);
                FinancialAdjustmentCommands actual = bindings.financialAdjustments();
                FinancialAdjustmentCommands fixtureCommands = new FinancialAdjustmentCommands() {
                    @Override
                    public Result postFinalQuantityAdjustment(Request request) {
                        if (fixture.consumeFailure()) {
                            throw new IllegalStateException("failure-injection-before-financial-adjustment");
                        }
                        return actual.postFinalQuantityAdjustment(request);
                    }

                    @Override
                    public Result post(Request request) {
                        return actual.post(request);
                    }
                };
                return new Bindings(bindings.customerAccounts(), bindings.creditExposure(),
                        bindings.creditReservations(), bindings.receivables(), bindings.receivablePaymentAccess(),
                        bindings.receivableApplications(), bindings.creditPayments(), fixtureCommands,
                        bindings.creditAccountConfigurations(), bindings.creditAccountConfigurationCommands());
            }
        };
    }

    public static final class TenantFinancialAdjustmentFixture {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean triggered = new AtomicBoolean();

        public void failNextFinalAdjustment() {
            triggered.set(false);
            if (!armed.compareAndSet(false, true)) {
                throw new IllegalStateException("A financial adjustment fixture action is already armed");
            }
        }

        public boolean failureWasTriggered() {
            return triggered.get();
        }

        private boolean consumeFailure() {
            boolean fail = armed.compareAndSet(true, false);
            if (fail) triggered.set(true);
            return fail;
        }
    }
}
