package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import com.nexa.api.warehouse.application.port.WarehouseOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

import static com.nexa.api.warehouse.infrastructure.persistence.WarehousePersistenceSupport.tenant;
import static com.nexa.api.warehouse.infrastructure.persistence.WarehousePersistenceSupport.workspace;

/** Infrastructure-only serialization of a Warehouse application event. */
@Repository
@Profile("!test")
public class WarehouseOutboxPersistenceAdapter implements WarehouseOutboxPort {
    private final JdbcTemplate jdbc;

    public WarehouseOutboxPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void fulfillmentReady(CurrentAccessContext context, WarehouseOperationsService.ReservationDetail reservation,
                                 String correlationId) {
        CanonicalOutbox.append(jdbc, "FULFILLMENT_READY", "InventoryReservation",
                java.util.UUID.fromString(reservation.id()), tenant(context), workspace(context), Instant.now(),
                correlationId == null ? "reservation-" + reservation.id() : correlationId, null, "1.0",
                Map.of("reservationId", java.util.UUID.fromString(reservation.id()),
                        "reservationVersion", reservation.version(),
                        "salesOrderId", java.util.UUID.fromString(reservation.salesOrderId()),
                        "salesOrderNumber", reservation.orderNumber(), "status", reservation.status()));
    }
}
