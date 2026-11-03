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
import org.springframework.mock.env.MockEnvironment;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.notifications.tenantdatabase.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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

    @Test
    void cloudProfileSelectsTheRealTenantAdapterOnlyWhenExplicitlyEnabled() {
        realTenantAdapterContext().withPropertyValues("nexa.tenant-business.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TenantBoundNotificationCommands.class);
                    assertThat(context.getBean(NotificationUseCase.class))
                            .isInstanceOf(TenantBoundNotificationCommands.class);
                    assertThat(context.getBean(PushSubscriptionUseCase.class))
                            .isSameAs(context.getBean(NotificationUseCase.class));
                    assertThat(context).doesNotHaveBean(TenantNotificationUnavailableAdapter.class);
                });
        realTenantAdapterContext().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TenantBoundNotificationCommands.class);
            assertThat(context.getBean(NotificationUseCase.class))
                    .isInstanceOf(TenantNotificationUnavailableAdapter.class);
        });
    }

    @Test
    void enabledCloudNotificationsCannotStartWithoutTenantRouter() {
        realTenantAdapterContext(false).withPropertyValues("nexa.tenant-business.notifications.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectedTenantBindingCannotReachNotificationPersistence() {
        var authority = mock(TenantBusinessDatabaseAuthority.class);
        var dataSources = mock(TenantBusinessDatabaseDataSourceFactory.class);
        var access = mock(CurrentAccessContext.class);
        when(access.tenantId()).thenReturn(
                new TenantId(UUID.randomUUID().toString()));
        when(access.workspaceId()).thenReturn(
                new WorkspaceId(UUID.randomUUID().toString()));
        when(access.membershipId()).thenReturn(
                new MembershipId(UUID.randomUUID().toString()));
        when(authority.requireReadyBinding(access)).thenThrow(
                new TenantBusinessDatabaseUnavailableException("Untrusted binding"));
        var bindings = mock(TenantNotificationBusinessBindingsFactory.class);
        var accounts = mock(TenantCustomerAccountQueryFactory.class);
        var traceability = mock(TenantBusinessTraceabilityBindingsFactory.class);
        try (var router = new TenantBusinessDatabaseRouter(
                authority, dataSources, 1)) {
            var commands = new TenantBoundNotificationCommands(router, bindings, accounts, traceability,
                    mock(SecurityAuditPort.class),
                    Clock.systemUTC(), new MockEnvironment().withProperty("spring.profiles.active", "local"));
            assertThatThrownBy(() -> commands.inbox(access, false, 10))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(dataSources, bindings, accounts, traceability);
        }
    }

    @Test
    void cloudSettingsMutationFailsBeforeTenantPersistenceOrSecurityAudit() {
        realTenantAdapterContext().withPropertyValues("nexa.tenant-business.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var access = mock(CurrentAccessContext.class);
                    UUID workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                    when(access.workspaceId()).thenReturn(new WorkspaceId(workspaceId.toString()));

                    assertThatThrownBy(() -> context.getBean(TenantBoundNotificationCommands.class)
                            .updateSettings(access, workspaceId,
                                    new NotificationPreferencesView(java.util.List.of(), 0), 0, "correlation"))
                            .isInstanceOfSatisfying(TechnicalFailureException.class,
                                    failure -> assertThat(failure.kind()).isEqualTo(
                                            TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE));
                    verifyNoInteractions(context.getBean(TenantBusinessDatabaseRouter.class),
                            context.getBean(TenantNotificationBusinessBindingsFactory.class),
                            context.getBean(TenantCustomerAccountQueryFactory.class),
                            context.getBean(TenantBusinessTraceabilityBindingsFactory.class),
                            context.getBean(SecurityAuditPort.class));
                });
    }

    private ApplicationContextRunner realTenantAdapterContext() {
        return realTenantAdapterContext(true);
    }

    private ApplicationContextRunner realTenantAdapterContext(boolean includeRouter) {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(TenantNotificationUnavailableConfiguration.class,
                        TenantNotificationAdapterRequiredConfiguration.class, TenantBoundNotificationCommands.class)
                .withPropertyValues("spring.profiles.active=tenant-cloud")
                .withBean(TenantNotificationBusinessBindingsFactory.class,
                        () -> mock(TenantNotificationBusinessBindingsFactory.class))
                .withBean(TenantCustomerAccountQueryFactory.class,
                        () -> mock(TenantCustomerAccountQueryFactory.class))
                .withBean(TenantBusinessTraceabilityBindingsFactory.class,
                        () -> mock(TenantBusinessTraceabilityBindingsFactory.class))
                .withBean(SecurityAuditPort.class,
                        () -> mock(SecurityAuditPort.class))
                .withBean(Clock.class, Clock::systemUTC);
        return includeRouter ? runner.withBean(
                TenantBusinessDatabaseRouter.class,
                () -> mock(TenantBusinessDatabaseRouter.class)) : runner;
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
