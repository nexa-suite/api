package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerSalesMessagePersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds message persistence to the exact routed Tenant database session. */
@FunctionalInterface
public interface TenantBuyerSalesMessagePersistenceFactory {
    BuyerSalesMessagePersistencePort bindTo(JdbcTemplate tenantJdbc);
}
