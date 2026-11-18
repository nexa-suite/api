package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.infrastructure.persistence.JdbcFcmPushDestinationStore;
import com.nexa.api.notifications.tenantdatabase.TenantPushProviderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Builds an FCM provider whose destination access stays bound to one Tenant JDBC session. */
final class FcmTenantPushProviderFactory implements TenantPushProviderFactory {
    private final String projectId;
    private final PushDestinationCipher destinationCipher;
    private final FcmPushProvider.AccessTokenSource credentials;
    private final ObjectMapper json;

    FcmTenantPushProviderFactory(String projectId, PushDestinationCipher destinationCipher,
            FcmPushProvider.AccessTokenSource credentials, ObjectMapper json) {
        FcmPushProvider.requireProjectId(projectId);
        this.projectId = projectId;
        this.destinationCipher = Objects.requireNonNull(destinationCipher, "Push destination cipher is required");
        this.credentials = Objects.requireNonNull(credentials, "Google FCM credentials are required");
        this.json = Objects.requireNonNull(json, "JSON mapper is required");
    }

    @Override
    public PushProviderPort bindTo(JdbcTemplate tenantJdbc) {
        JdbcFcmPushDestinationStore destinations = new JdbcFcmPushDestinationStore(tenantJdbc, destinationCipher);
        return new FcmPushProvider(projectId, destinations, destinations, credentials, json);
    }
}
