package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds the existing BC-02 query adapter to the exact JDBC session supplied by runtime composition. */
@Component
@Profile("!test")
public final class JdbcTenantCustomerAccountQueryFactory implements TenantCustomerAccountQueryFactory {
	@Override
	public CustomerAccountQuery bindTo(JdbcTemplate tenantJdbc) {
		return new ClientAccountPersistenceAdapter(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
	}
}
