package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.notifications.application.model.NotificationModels.ProjectedNotification;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.tenantdatabase.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.notifications.application.port.out.NotificationProjectionSourceEventQuery;
import com.nexa.api.notifications.application.service.NotificationService;
import com.nexa.api.notifications.application.service.PushDeliveryRetryPolicy;
import com.nexa.api.notifications.application.service.PushSubscriptionService;
import com.nexa.api.notifications.application.service.TenantNotificationProjectionService;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
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
    private final Clock clock;
    private final PushProviderPort pushProvider;
    private final PushDeliveryRetryPolicy pushRetryPolicy;

    public JdbcTenantNotificationBusinessBindingsFactory(Clock clock, PushProviderPort pushProvider,
            PushDeliveryRetryPolicy pushRetryPolicy) {
        this.clock = Objects.requireNonNull(clock, "Application clock is required");
        this.pushProvider = Objects.requireNonNull(pushProvider, "Push provider is required");
        this.pushRetryPolicy = Objects.requireNonNull(pushRetryPolicy, "Push retry policy is required");
    }

    @Override
    public Bindings bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
            PreflightedNotificationRecipients preflightedRecipients, CustomerAccountQuery customerAccounts,
            CanonicalOutboxPort tenantOutbox) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        Objects.requireNonNull(tenantTransactionManager, "Tenant transaction manager is required");
        PreflightedNotificationRecipients recipients = Objects.requireNonNull(preflightedRecipients,
                "Central recipient preflight is required");
        CustomerAccountQuery accountQuery = Objects.requireNonNull(customerAccounts,
                "Tenant BC-02 account query is required");
        CanonicalOutboxPort outbox = Objects.requireNonNull(tenantOutbox, "Tenant canonical outbox is required");

        JdbcNotificationInboxAdapter inbox = new JdbcNotificationInboxAdapter(jdbc, recipients);
        JdbcNotificationPreferenceAdapter preferences = new JdbcNotificationPreferenceAdapter(jdbc,
                recipients.tenantId(), recipients.workspaceId());
        JdbcPushSubscriptionAdapter subscriptions = new JdbcPushSubscriptionAdapter(jdbc);
        PushRoutingService pushRouting = new PushRoutingService(subscriptions, pushProvider, tenantTransactionManager,
                pushRetryPolicy, new JdbcPushTransactionScopeGuard(jdbc), recipients.tenantId(),
                recipients.workspaceId());
        NotificationService service = new NotificationService(inbox, preferences, accountQuery, pushRouting,
                null, new JdbcPushNotificationOutboxAdapter(outbox));
        PushSubscriptionService pushSubscriptions = new PushSubscriptionService(subscriptions, clock);
        NotificationProjectionSourceEventQuery sourceEvents = new JdbcNotificationProjectionSourceEventQueryAdapter(jdbc);
        NotificationProjectionPort projection = new TenantNotificationProjectionService(recipients, sourceEvents, service);
        return new Bindings(service, pushSubscriptions, projection, preferences);
    }
}
