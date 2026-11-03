package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryReservationExpiryWorkerFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/** Keeps reservation-expiry persistence composition inside BC-05. */
@Component
@Profile("!test")
public final class JdbcTenantInventoryReservationExpiryWorkerFactory
        implements TenantInventoryReservationExpiryWorkerFactory {
    @Override
    public TenantInventoryReservationExpiryWorker bindTo(JdbcTemplate tenantJdbc,
            ChangeEventPersistencePort tenantChangeEvents, PlatformTransactionManager transactionManager) {
        WarehouseReservationPersistenceAdapter persistence = new WarehouseReservationPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Routed Tenant JDBC session is required"),
                Objects.requireNonNull(tenantChangeEvents, "Tenant change-event persistence is required"),
                Objects.requireNonNull(transactionManager, "Tenant transaction manager is required"));
        return persistence::expireReservationsForScope;
    }
}
