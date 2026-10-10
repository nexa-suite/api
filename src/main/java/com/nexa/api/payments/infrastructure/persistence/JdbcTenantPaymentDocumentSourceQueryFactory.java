package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import com.nexa.api.payments.tenantdatabase.TenantPaymentDocumentSourceQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates a BC-08 document source over the exact Tenant JDBC session supplied by the router. */
@Component
@Profile("!test")
public final class JdbcTenantPaymentDocumentSourceQueryFactory implements TenantPaymentDocumentSourceQueryFactory {
    @Override
    public PaymentDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcPaymentDocumentSourceQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
