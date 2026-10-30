package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import com.nexa.api.notifications.application.publicapi.TenantNotificationSettingsCommands;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TenantNotificationUnavailableConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TenantNotificationUnavailableConfiguration.class,
                    TenantNotificationAdapterRequiredConfiguration.class)
            .withBean(NotificationUseCase.class, () -> mock(NotificationUseCase.class))
            .withBean(PushSubscriptionUseCase.class, () -> mock(PushSubscriptionUseCase.class))
            .withBean(TenantNotificationSettingsCommands.class,
                    () -> mock(TenantNotificationSettingsCommands.class));

    @Test
    void disabledTenantFeatureFailsClosedInsteadOfSelectingCentralNotificationUseCases() {
        contextRunner.withPropertyValues("nexa.tenant-business.notifications.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertUnavailableNotificationRequests(context.getBean(NotificationUseCase.class),
                            context.getBean(PushSubscriptionUseCase.class),
                            context.getBean(TenantNotificationSettingsCommands.class));
                    context.getBeansOfType(NotificationUseCase.class).values().stream()
                            .filter(candidate -> candidate != context.getBean(NotificationUseCase.class))
                            .forEach(candidate -> verifyNoInteractions(candidate));
                });
    }

    @Test
    void enabledTenantFeatureWithoutItsAdapterAlsoFailsClosed() {
        contextRunner.withPropertyValues("nexa.tenant-business.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("TenantBoundNotificationCommands");
                });
    }

    @Test
    void configuredTenantAdapterSuppressesTheUnavailableFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(TenantNotificationUnavailableConfiguration.class,
                        TenantNotificationAdapterRequiredConfiguration.class, TenantAdapterConfiguration.class)
                .withPropertyValues("nexa.tenant-business.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TenantNotificationUnavailableAdapter.class)).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TenantAdapterConfiguration {
        @Bean
        TenantBoundNotificationCommands tenantBoundNotificationCommands() {
            return mock(TenantBoundNotificationCommands.class);
        }
    }

    @Test
    void tenantSettingsCompatibilityControllerDoesNotReachCentralConfiguration() {
        var controller = new com.nexa.api.tenantaccessgovernance.tenantmanagement.presentation.rest
                .CentralTenantNotificationSettingsController();

        assertThatThrownBy(() -> controller.notifications(null, UUID.randomUUID().toString()))
                .isInstanceOf(TechnicalFailureException.class);
        assertThatThrownBy(() -> controller.updateNotifications(null, UUID.randomUUID().toString(), "1",
                new com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels
                        .NotificationSettingsView(java.util.List.of(), 0), null))
                .isInstanceOf(TechnicalFailureException.class);
    }

    private static void assertUnavailableNotificationRequests(NotificationUseCase notifications,
            PushSubscriptionUseCase pushSubscriptions, TenantNotificationSettingsCommands settings) {
        assertThat(notifications).isInstanceOf(TenantNotificationUnavailableAdapter.class);
        assertThat(pushSubscriptions).isInstanceOf(TenantNotificationUnavailableAdapter.class);
        assertThat(settings).isInstanceOf(TenantNotificationUnavailableAdapter.class);
        assertThatThrownBy(() -> notifications.inbox(null, false, 10))
                .isInstanceOf(TechnicalFailureException.class);
        assertThatThrownBy(() -> pushSubscriptions.register(null, "NATIVE", "installation", "IOS", "token", "key"))
                .isInstanceOf(TechnicalFailureException.class);
        assertThatThrownBy(() -> settings.updateSettings(null, UUID.randomUUID(),
                new NotificationPreferencesView(java.util.List.of(), 0), 0, "correlation"))
                .isInstanceOf(TechnicalFailureException.class);
    }
}
