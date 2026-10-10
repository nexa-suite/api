package com.nexa.api.payments.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeTenantCommands;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-08 persistence to a JDBC session supplied by the Tenant router callback. */
public interface BuyerWalletTenantDatabaseAdapterFactory {
    BuyerWalletReadPort bindReadTo(JdbcTemplate tenantJdbc);

    BuyerWalletReservationCommands bindReservationCommandsTo(
            JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts);

    BuyerWalletRechargeTenantCommands bindRechargeCommandsTo(
            JdbcTemplate tenantJdbc, CustomerAccountQuery tenantCustomerAccounts);
}
