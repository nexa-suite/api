package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses Payments' confirmation adapter with the current callback's Tenant receivable query. */
@Component
@Profile("!test")
public final class JdbcTenantPaymentConfirmationQueryFactory implements TenantPaymentConfirmationQueryFactory {
    @Override
    public PaymentConfirmationQuery bindTo(JdbcTemplate tenantJdbc, ReceivablePaymentAccess tenantReceivables) {
        return new JdbcPaymentConfirmationQuery(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantReceivables, "Tenant receivable access is required"));
    }
}
