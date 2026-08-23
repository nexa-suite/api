package com.nexa.api.customerrelationships.infrastructure;

import com.nexa.api.customerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CustomerRelationshipsRuntimeConfiguration {
    @Bean
    ClientAccountUseCase clientAccountUseCase(
            ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyerMemberships) {
        return new ClientAccountService(persistence, buyerMemberships);
    }

    @Bean
    ClientAccountAddressUseCase clientAccountAddressUseCase(
            ClientAccountAddressPersistencePort persistence, CustomerAccountQuery accounts) {
        return new ClientAccountAddressService(persistence, accounts);
    }
}
