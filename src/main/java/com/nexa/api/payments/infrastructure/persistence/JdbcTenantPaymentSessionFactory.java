package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.TenantPaymentSession;
import com.nexa.api.payments.tenantdatabase.TenantPaymentSessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/** Payments-owned assembly for a short-lived Tenant session. */
@Component
@Profile("local")
public final class JdbcTenantPaymentSessionFactory implements TenantPaymentSessionFactory {
    private final StripePaymentProvider stripe;
    private final String publishableKey;
    private final String webhookSecret;

    public JdbcTenantPaymentSessionFactory(StripePaymentProvider stripe,
            @Value("${nexa.payments.publishable-key:}") String publishableKey,
            @Value("${nexa.payments.webhook-secret:}") String webhookSecret) {
        this.stripe = Objects.requireNonNull(stripe);
        this.publishableKey = publishableKey == null ? "" : publishableKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    @Override
    public TenantPaymentSession bindTo(JdbcTemplate tenantJdbc,
            PlatformTransactionManager tenantTransactionManager, Dependencies dependencies) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        PlatformTransactionManager transactionManager = Objects.requireNonNull(tenantTransactionManager,
                "Tenant transaction manager is required");
        Dependencies bound = Objects.requireNonNull(dependencies, "Tenant Payments owner bindings are required");
        return new PaymentService(jdbc, stripe, publishableKey, webhookSecret, transactionManager, null,
                bound.receivableApplications(), bound.creditPayments(), bound.receivables(), bound.documents(),
                bound.businessEvidence(), bound.customerAccounts(), bound.salesOrders(), bound.canonicalOutbox(),
                bound.receivableAccess());
    }
}
