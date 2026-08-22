package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Supplies an explicit disabled adapter when no provider bean is enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class PaymentProviderRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(StripePaymentProvider.class)
    StripePaymentProvider disabledStripePaymentProvider(Environment environment) {
        String provider = environment.getProperty("nexa.payments.provider", "disabled").trim();
        if (provider.isBlank() || "disabled".equalsIgnoreCase(provider)) return new DisabledStripePaymentProvider();
        throw new IllegalStateException("No payment provider bean is available for '" + provider + "'");
    }
}
