package com.nexa.api.customerbuyerrelationships.infrastructure;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CustomerRelationshipsRuntimeConfiguration {
    @Bean
    ClientAccountUseCase clientAccountUseCase(
            ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyerMemberships,
            ActiveSalesMembershipDirectory salesMemberships, BusinessTraceabilityCommands traceability) {
        return new ClientAccountService(persistence, buyerMemberships, salesMemberships, traceability);
    }

    @Bean
    ClientAccountAddressUseCase clientAccountAddressUseCase(
            ClientAccountAddressPersistencePort persistence, CustomerAccountQuery accounts) {
        return new ClientAccountAddressService(persistence, accounts);
    }
}
