package com.nexa.api.payments.infrastructure.stripe;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Rejects ambiguous payment-provider configuration with an actionable startup error. */
@Component
@Profile("!test")
public final class PaymentRuntimeConfigurationValidator {
    private static final Set<String> SUPPORTED = Set.of("disabled", "stripe", "deterministic");

    public PaymentRuntimeConfigurationValidator(Environment environment) {
        String provider = environment.getProperty("nexa.payments.provider", "disabled").trim().toLowerCase(Locale.ROOT);
        if (provider.isBlank()) provider = "disabled";
        if (!SUPPORTED.contains(provider)) {
            throw new IllegalStateException("Unsupported payment provider '" + provider + "'; use disabled or stripe");
        }
        if ("deterministic".equals(provider) && !environment.acceptsProfiles("local")) {
            throw new IllegalStateException("The deterministic payment provider is available only with the local profile");
        }
        positive(environment, "nexa.payments.connect-timeout-ms", "5000");
        positive(environment, "nexa.payments.read-timeout-ms", "10000");
        nonNegative(environment, "nexa.payments.max-network-retries", "0");
    }

    private static void positive(Environment environment, String key, String defaultValue) {
        int value = Integer.parseInt(environment.getProperty(key, defaultValue));
        if (value <= 0) throw new IllegalStateException(key + " must be positive");
    }

    private static void nonNegative(Environment environment, String key, String defaultValue) {
        int value = Integer.parseInt(environment.getProperty(key, defaultValue));
        if (value < 0) throw new IllegalStateException(key + " cannot be negative");
    }
}
