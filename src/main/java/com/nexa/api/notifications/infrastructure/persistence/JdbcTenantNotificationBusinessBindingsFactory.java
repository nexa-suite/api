package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.notifications.application.model.NotificationModels.ProjectedNotification;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.application.publicapi.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.notifications.application.port.out.NotificationProjectionSourceEventQuery;
import com.nexa.api.notifications.application.service.NotificationService;
import com.nexa.api.notifications.application.service.PushSubscriptionService;
import com.nexa.api.notifications.application.service.TenantNotificationProjectionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Objects;

/** Binds existing BC-10 behavior to a single verified Tenant JDBC/transaction pair. */
@Component
@org.springframework.context.annotation.Profile("!test")
public final class JdbcTenantNotificationBusinessBindingsFactory
        implements TenantNotificationBusinessBindingsFactory {
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final Clock clock;

    public JdbcTenantNotificationBusinessBindingsFactory(TenantCustomerAccountQueryFactory customerAccounts,
            Clock clock) {
        this.customerAccounts = Objects.requireNonNull(customerAccounts, "Tenant BC-02 account factory is required");
        this.clock = Objects.requireNonNull(clock, "Application clock is required");
    }

    @Override
    public Bindings bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
            PreflightedNotificationRecipients preflightedRecipients) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        Objects.requireNonNull(tenantTransactionManager, "Tenant transaction manager is required");
        PreflightedNotificationRecipients recipients = Objects.requireNonNull(preflightedRecipients,
                "Central recipient preflight is required");

        JdbcNotificationInboxAdapter inbox = new JdbcNotificationInboxAdapter(jdbc, recipients);
        JdbcNotificationPreferenceAdapter preferences = new JdbcNotificationPreferenceAdapter(jdbc,
                recipients.tenantId(), recipients.workspaceId());
        JdbcPushSubscriptionAdapter subscriptions = new JdbcPushSubscriptionAdapter(jdbc);
        NotificationService service = new NotificationService(inbox, preferences, customerAccounts.bindTo(jdbc));
        PushSubscriptionService pushSubscriptions = new PushSubscriptionService(subscriptions, clock);
        NotificationProjectionSourceEventQuery sourceEvents = new JdbcNotificationProjectionSourceEventQueryAdapter(jdbc);
        NotificationProjectionPort projection = new TenantNotificationProjectionService(recipients, sourceEvents, service);
        return new Bindings(service, pushSubscriptions, projection, preferences);
    }
}
