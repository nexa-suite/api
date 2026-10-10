package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeTenantCommands;
import com.nexa.api.payments.infrastructure.persistence.JdbcBuyerWalletReservationCommands;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates adapters only for the explicit Tenant JDBC callback session supplied by composition. */
@Component
public final class JdbcBuyerWalletTenantDatabaseAdapterFactory implements BuyerWalletTenantDatabaseAdapterFactory {
    @Override
    public BuyerWalletReadPort bindReadTo(JdbcTemplate tenantJdbc) {
        return new JdbcBuyerWalletReadAdapter(Objects.requireNonNull(tenantJdbc));
    }

    @Override
    public BuyerWalletReservationCommands bindReservationCommandsTo(
            JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts) {
        return new JdbcBuyerWalletReservationCommands(Objects.requireNonNull(tenantJdbc),
                Objects.requireNonNull(tenantCustomerAccounts));
    }

    @Override
    public BuyerWalletRechargeTenantCommands bindRechargeCommandsTo(
            JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts) {
        return new JdbcBuyerWalletRechargeCommands(Objects.requireNonNull(tenantJdbc),
                Objects.requireNonNull(tenantCustomerAccounts));
    }
}
