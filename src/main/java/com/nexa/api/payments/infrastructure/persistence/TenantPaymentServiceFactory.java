package com.nexa.api.payments.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Binds Payment owner behavior to a router-owned Tenant JDBC session. */
public interface TenantPaymentServiceFactory {
    TenantPaymentSession bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager);
}
