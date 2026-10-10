package com.nexa.api.payments.tenantdatabase;

import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds payment confirmation reads to the same Tenant database callback as their receivables. */
@FunctionalInterface
public interface TenantPaymentConfirmationQueryFactory {
    PaymentConfirmationQuery bindTo(JdbcTemplate tenantJdbc, ReceivablePaymentAccess tenantReceivables);
}
