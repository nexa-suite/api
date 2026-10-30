package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerAddressPersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates personal Buyer address persistence on the router-owned Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantBuyerAddressPersistenceFactory implements TenantBuyerAddressPersistenceFactory {
    @Override
    public BuyerAddressPersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcBuyerPersonalAddressPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
