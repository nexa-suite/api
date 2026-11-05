package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds Buyer personal-address persistence to one router-owned Tenant JDBC session. */
@FunctionalInterface
public interface TenantBuyerAddressPersistenceFactory {
    BuyerAddressPersistencePort bindTo(JdbcTemplate tenantJdbc);
}
