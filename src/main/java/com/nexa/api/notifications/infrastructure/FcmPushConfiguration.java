package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.tenantdatabase.TenantPushProviderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import com.nexa.api.notifications.infrastructure.PushDestinationCipher;
import tools.jackson.databind.ObjectMapper;

/** FCM is opt-in and is bound only to the active Tenant business database session. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.notifications.push", name = "provider", havingValue = "fcm")
public class FcmPushConfiguration {
    public FcmPushConfiguration(Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        boolean cloud = environment.acceptsProfiles(Profiles.of("tenant-cloud"));
        if (local == cloud) throw new IllegalStateException("FCM push delivery requires one supported Tenant runtime");
        if (!environment.getProperty("nexa.tenant-business.notifications.enabled", Boolean.class, false)) {
            throw new IllegalStateException("FCM push delivery requires Tenant notifications to be enabled");
        }
        if (local && !environment.getProperty("nexa.tenant-business.business-traceability.enabled", Boolean.class, false)
                || cloud && !environment.getProperty("nexa.tenant-business.cloud-notification-push.enabled", Boolean.class, false)) {
            throw new IllegalStateException("FCM push delivery requires its dedicated Tenant worker to be enabled");
        }
    }

    @Bean
    TenantPushProviderFactory fcmTenantPushProviderFactory(
            @Value("${nexa.notifications.push.fcm.project-id:}") String projectId,
            PushDestinationCipher destinationCipher, ObjectMapper json) {
        FcmPushProvider.requireProjectId(projectId);
        return new FcmTenantPushProviderFactory(projectId, destinationCipher, new GoogleFcmAccessTokenSource(), json);
    }
}
