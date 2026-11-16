package com.nexa.api.notifications.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FcmPushConfigurationTests {
    @Test
    void rejectsFcmOutsideTheLocalTenantRuntime() {
        assertThatThrownBy(() -> new FcmPushConfiguration(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FCM push delivery currently requires the local Tenant database runtime");
    }

    @Test
    void requiresBothTenantNotificationsAndTraceabilityCapabilities() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatThrownBy(() -> new FcmPushConfiguration(local))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FCM push delivery requires Tenant notifications to be enabled");

        local.setProperty("nexa.tenant-business.notifications.enabled", "true");
        assertThatThrownBy(() -> new FcmPushConfiguration(local))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FCM push delivery requires the Tenant traceability worker to be enabled");

        local.setProperty("nexa.tenant-business.business-traceability.enabled", "true");
        assertThatCode(() -> new FcmPushConfiguration(local)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCloudProfileEvenIfLocalProfileIsAlsoActive() {
        MockEnvironment localAndCloud = new MockEnvironment();
        localAndCloud.setActiveProfiles("local", "tenant-cloud");
        localAndCloud.setProperty("nexa.tenant-business.notifications.enabled", "true");
        localAndCloud.setProperty("nexa.tenant-business.business-traceability.enabled", "true");

        assertThatThrownBy(() -> new FcmPushConfiguration(localAndCloud))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FCM push delivery currently requires the local Tenant database runtime");
    }
}
