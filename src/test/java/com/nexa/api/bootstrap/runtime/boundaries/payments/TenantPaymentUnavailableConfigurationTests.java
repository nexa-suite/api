package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.payments.application.publicapi.PaymentUseCases;
import com.nexa.api.payments.application.publicapi.webhooks.PaymentWebhookIntake;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TenantPaymentUnavailableConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TenantPaymentUnavailableConfiguration.class,
                    TenantPaymentAdapterRequiredConfiguration.class)
            .withBean(PaymentUseCases.class, () -> mock(PaymentUseCases.class))
            .withBean(PaymentWebhookIntake.class, () -> mock(PaymentWebhookIntake.class));

    @Test
    void disabledTenantPaymentsFailClosedInsteadOfSelectingCentralUseCases() {
        contextRunner.withPropertyValues("nexa.tenant-business.payments.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertTenantPaymentRequestsUnavailable(context);
                });
    }

    @Test
    void enabledTenantPaymentsWithoutTheAdapterAlsoFailClosed() {
        contextRunner.withPropertyValues("nexa.tenant-business.payments.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("TenantPaymentPersistenceAdapter");
                });
    }

    @Test
    void configuredTenantAdapterSuppressesTheUnavailableFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(TenantPaymentUnavailableConfiguration.class,
                        TenantPaymentAdapterRequiredConfiguration.class, TenantAdapterConfiguration.class)
                .withPropertyValues("nexa.tenant-business.payments.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TenantPaymentUnavailableAdapter.class)).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TenantAdapterConfiguration {
        @Bean
        TenantPaymentPersistenceAdapter tenantPaymentPersistenceAdapter() {
            return mock(TenantPaymentPersistenceAdapter.class);
        }
    }

    private static void assertTenantPaymentRequestsUnavailable(
            org.springframework.context.ApplicationContext context) {
        PaymentUseCases useCases = context.getBean(PaymentUseCases.class);
        PaymentWebhookIntake webhook = context.getBean(PaymentWebhookIntake.class);
        assertThat(useCases).isInstanceOf(TenantPaymentUnavailableAdapter.class);
        assertThat(webhook).isInstanceOf(TenantPaymentUnavailableAdapter.class);
        assertThatThrownBy(() -> useCases.listReceivables(null, 0, 25))
                .isInstanceOf(TechnicalFailureException.class);
        assertThatThrownBy(() -> webhook.receiveStripeWebhook("payload", "signature"))
                .isInstanceOf(TechnicalFailureException.class);
        context.getBeansOfType(PaymentUseCases.class).values().stream()
                .filter(candidate -> candidate != useCases)
                .forEach(candidate -> verifyNoInteractions(candidate));
        context.getBeansOfType(PaymentWebhookIntake.class).values().stream()
                .filter(candidate -> candidate != webhook)
                .forEach(candidate -> verifyNoInteractions(candidate));
    }
}
