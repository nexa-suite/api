package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAddressQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses the existing BC-02 address adapter on the caller's Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantCustomerAddressQueryFactory implements TenantCustomerAddressQueryFactory {
    @Override
    public CustomerAddressQuery bindTo(JdbcTemplate tenantJdbc) {
        return new ClientAccountAddressPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
