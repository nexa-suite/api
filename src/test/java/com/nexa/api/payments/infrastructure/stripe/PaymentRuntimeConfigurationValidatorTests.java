package com.nexa.api.payments.infrastructure.stripe;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRuntimeConfigurationValidatorTests {
    @Test
    void acceptsExplicitlyDisabledProviderOutsideLocalProfile() {
        new PaymentRuntimeConfigurationValidator(new MockEnvironment().withProperty("nexa.payments.provider", "disabled"));
    }

    @Test
    void rejectsUnknownProviderWithActionableMessage() {
        assertThatThrownBy(() -> new PaymentRuntimeConfigurationValidator(
                new MockEnvironment().withProperty("nexa.payments.provider", "unknown")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported payment provider 'unknown'; use disabled or stripe");
    }

    @Test
    void rejectsDeterministicProviderOutsideLocalProfile() {
        assertThatThrownBy(() -> new PaymentRuntimeConfigurationValidator(
                new MockEnvironment().withProperty("nexa.payments.provider", "deterministic")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The deterministic payment provider is available only with the local profile");
    }

    @Test
    void acceptsDeterministicProviderInLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("nexa.payments.provider", "deterministic");

        new PaymentRuntimeConfigurationValidator(environment);
    }
}
