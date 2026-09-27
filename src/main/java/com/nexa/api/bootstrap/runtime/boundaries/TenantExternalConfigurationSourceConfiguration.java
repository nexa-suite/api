package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.salescommitment.application.publicapi.SalesUsageQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantExternalConfigurationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.UUID;

/** Composes owner APIs for Tenant Management without owning their persistence. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantExternalConfigurationSourceConfiguration {
    @Bean
    TenantExternalConfigurationSource tenantExternalConfigurationSource(
            NotificationPreferenceAccess notifications, SalesUsageQuery salesUsage) {
        return new TenantExternalConfigurationSource() {
            @Override
            public List<Preference> notificationPreferences(UUID workspaceId) {
                return notifications.notificationPreferences(workspaceId).stream()
                        .map(value -> new Preference(value.eventCategory(), value.channel(), value.enabled(),
                                value.version()))
                        .toList();
            }

            @Override
            public long notificationVersion(UUID workspaceId) {
                return notifications.notificationVersion(workspaceId);
            }

            @Override
            public int updateNotificationPreference(UUID workspaceId, Preference preference) {
                return notifications.updateNotificationPreference(workspaceId,
                        new NotificationPreferenceAccess.Preference(preference.eventCategory(), preference.channel(),
                                preference.enabled(), preference.version()));
            }

            @Override
            public void ensureNotificationDefaults(UUID workspaceId) {
                notifications.ensureNotificationDefaults(workspaceId);
            }

            @Override
            public long salesTransactionCount(UUID tenantId) {
                return salesUsage.countTransactions(tenantId);
            }
        };
    }
}
