package com.nexa.api.notifications.application.publicapi;

import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Binds BC-10 operations to the exact Tenant JDBC session supplied by a verified request router or worker.
 * Implementations must not resolve a connection, central membership, or fallback persistence of their own.
 */
@org.springframework.modulith.NamedInterface(value = "notification-projections", propagate = false)
public interface TenantNotificationBusinessBindingsFactory {
    Bindings bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
            PreflightedNotificationRecipients preflightedRecipients);

    record Bindings(NotificationUseCase notifications, PushSubscriptionUseCase pushSubscriptions,
                    NotificationProjectionPort projection, NotificationPreferenceAccess preferences) {
        public Bindings {
            java.util.Objects.requireNonNull(notifications, "Tenant notification use case is required");
            java.util.Objects.requireNonNull(pushSubscriptions, "Tenant push-subscription use case is required");
            java.util.Objects.requireNonNull(projection, "Tenant notification projection port is required");
            java.util.Objects.requireNonNull(preferences, "Tenant notification preferences are required");
        }
    }
}
