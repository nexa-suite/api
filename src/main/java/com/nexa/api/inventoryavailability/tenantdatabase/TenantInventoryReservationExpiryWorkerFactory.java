package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

/** Creates the BC-05 expiry operation on the caller's verified Tenant database session. */
public interface TenantInventoryReservationExpiryWorkerFactory {
    TenantInventoryReservationExpiryWorker bindTo(JdbcTemplate tenantJdbc,
            ChangeEventPersistencePort tenantChangeEvents, PlatformTransactionManager transactionManager);

    @FunctionalInterface
    interface TenantInventoryReservationExpiryWorker {
        void expireReservationsForScope(UUID tenantId, UUID workspaceId);
    }
}
