package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.shared.events.TenantOutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Set;

/**
 * Technical dispatch seam for an owner callback over a verified Tenant outbox.
 * Tenant reads happen in the short routed read; {@link AfterTenantRead#preflight()}
 * runs after that transaction closes; the returned callback runs in a new Tenant
 * transaction with its per-consumer inbox receipt.
 */
public interface TenantOutboxEventConsumer {
    String consumerName();

    Set<String> eventTypes();

    /** Tenant-only reads. Do not retain JDBC, transaction, or data-source objects in the result. */
    AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event);

    @FunctionalInterface
    interface AfterTenantRead {
        PreparedCallback preflight();
    }

    /** Carries only immutable central preflight facts into the next routed transaction. */
    @FunctionalInterface
    interface PreparedCallback {
        void consume(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager,
                     TenantOutboxEvent event);
    }
}
