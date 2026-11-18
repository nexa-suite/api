package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.notifications.tenantdatabase.TenantPushProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FcmPushRuntimeCompositionTests {
    @Test
    void fcmStartsWithOnlyTenantBoundDeliveryAndNoLegacyCentralListener() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fcm", Map.of(
                    "nexa.notifications.push.provider", "fcm",
                    "nexa.notifications.push.fcm.project-id", "nexa-project",
                    "nexa.tenant-business.notifications.enabled", "true",
                    "nexa.tenant-business.business-traceability.enabled", "true")));
            context.register(FcmPushConfiguration.class, PushNotificationAfterCommitListener.class);
            context.registerBean(PushDestinationCipher.class, () -> new PushDestinationCipher(
                    "test-destination-key-012345678901234567890123456789", "test-v1", ""));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.refresh();

            assertThat(context.getBeansOfType(TenantPushProviderFactory.class)).hasSize(1);
            assertThat(context.getBeansOfType(PushProviderPort.class)).isEmpty();
            assertThat(context.getBeansOfType(PushRoutingService.class)).isEmpty();
            assertThat(context.getBeansOfType(PushNotificationAfterCommitListener.class)).isEmpty();
        }
    }

    @Test
    void defaultDeferredProviderRetainsTheLegacyAfterCommitListener() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PushNotificationAfterCommitListener.class);
            context.registerBean(PushRoutingService.class, () -> mock(PushRoutingService.class));
            context.refresh();

            assertThat(context.getBeansOfType(PushNotificationAfterCommitListener.class)).hasSize(1);
        }
    }
}
