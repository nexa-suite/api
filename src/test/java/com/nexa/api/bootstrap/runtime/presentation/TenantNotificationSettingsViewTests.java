package com.nexa.api.bootstrap.runtime.presentation;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantNotificationSettingsViewTests {

    @Test
    void preservesTheTenantSettingsWireShapeWhileUsingTheNotificationsPublicModel() {
        var preference = new NotificationPreferenceView("ORDER_STATUS", "IN_APP", true, 4);
        var notifications = new NotificationPreferencesView(List.of(preference), 5);

        var response = TenantNotificationSettingsView.from(notifications);

        assertThat(response).isEqualTo(new TenantNotificationSettingsView(
                List.of(new TenantNotificationSettingsView.Preference("ORDER_STATUS", "IN_APP", true, 4)), 5));
        assertThat(response.toPreferences(5)).isEqualTo(notifications);
    }
}
