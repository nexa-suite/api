package com.nexa.api.bootstrap.runtime.boundaries.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Prevents an enabled Tenant payment feature from starting without its Tenant persistence adapter. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public class TenantPaymentAdapterRequiredConfiguration {
    public TenantPaymentAdapterRequiredConfiguration(TenantPaymentPersistenceAdapter tenantPayments) {
        if (tenantPayments == null) throw new IllegalStateException("Tenant payment adapter is required");
    }
}
