package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes credit eligibility against the central account query for central runtime adapters. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class CentralCreditEligibilitySourceConfiguration {
    @Bean
    CreditEligibilitySource centralCreditEligibilitySource(CustomerAccountQuery centralCustomerAccounts) {
        // The central ClientAccountPersistenceAdapter also implements CustomerTermsQuery. If that
        // contract is absent, the factory returns a source that denies credit eligibility.
        return TenantCreditEligibilitySourceFactory.bindTo(centralCustomerAccounts);
    }
}
