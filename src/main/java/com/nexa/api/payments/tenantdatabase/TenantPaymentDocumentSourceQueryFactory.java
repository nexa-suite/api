package com.nexa.api.payments.tenantdatabase;

import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-08's document source query to one router-owned Tenant session. */
@FunctionalInterface
public interface TenantPaymentDocumentSourceQueryFactory {
    PaymentDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc);
}
