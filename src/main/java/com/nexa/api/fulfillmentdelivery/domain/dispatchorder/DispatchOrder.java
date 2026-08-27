package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Logistics aggregate. Commercial and inventory aggregates are referenced by identity only. */
public final class DispatchOrder {
    private final DispatchOrderId id;
    private final DispatchNumber number;
    private final InventoryReservationId reservationId;
    private final SalesOrderId salesOrderId;
    private final ClientAccountId clientAccountId;
    private final DestinationSnapshot destination;
    private DispatchStatus status;
    private TransportAssignment assignment;
    private DeliveryWindow deliveryWindow;
    private Instant eta;
    private long version;

    private DispatchOrder(DispatchOrderId id, DispatchNumber number, InventoryReservationId reservationId, SalesOrderId salesOrderId,
                          ClientAccountId clientAccountId, DestinationSnapshot destination, DispatchStatus status,
                          TransportAssignment assignment, DeliveryWindow deliveryWindow, Instant eta, long version) {
        this.id = Objects.requireNonNull(id); this.number = Objects.requireNonNull(number);
        this.reservationId = Objects.requireNonNull(reservationId); this.salesOrderId = Objects.requireNonNull(salesOrderId);
        this.clientAccountId = Objects.requireNonNull(clientAccountId); this.destination = destination;
        this.status = Objects.requireNonNull(status); this.assignment = assignment; this.deliveryWindow = deliveryWindow;
        this.eta = eta; this.version = version;
    }

    public static DispatchOrder create(UUID id, DispatchNumber number, InventoryReservationId reservationId,
                                       SalesOrderId salesOrderId, ClientAccountId clientAccountId,
                                       DestinationSnapshot destination) {
        return new DispatchOrder(new DispatchOrderId(id), number, reservationId, salesOrderId, clientAccountId, destination,
                DispatchStatus.READY_FOR_OPERATIONS, null, null, null, 0);
    }

    public static DispatchOrder rehydrate(UUID id, DispatchNumber number, InventoryReservationId reservationId,
                                          SalesOrderId salesOrderId, ClientAccountId clientAccountId,
                                          DestinationSnapshot destination, DispatchStatus status,
                                          TransportAssignment assignment, DeliveryWindow deliveryWindow, Instant eta,
                                          long version) {
        return new DispatchOrder(new DispatchOrderId(id), number, reservationId, salesOrderId, clientAccountId, destination, status,
                assignment, deliveryWindow, eta, version);
    }

    public void startPreparation() { transition(DispatchStatus.READY_FOR_OPERATIONS, DispatchStatus.PREPARING); }
    public void assign(TransportAssignment value) {
        require(DispatchStatus.PREPARING); assignment = Objects.requireNonNull(value); status = DispatchStatus.ASSIGNED;
    }
    public void schedule(DeliveryWindow value, Instant newEta) {
        if (status != DispatchStatus.ASSIGNED && status != DispatchStatus.REPROGRAMMED) throw invalid();
        deliveryWindow = Objects.requireNonNull(value); eta = newEta; status = DispatchStatus.SCHEDULED;
    }
    public void markReadyForRoute() { if (status != DispatchStatus.SCHEDULED) throw invalid(); if (assignment == null || deliveryWindow == null) throw invalid(); status = DispatchStatus.READY_FOR_ROUTE; }
    public void startRoute() { require(DispatchStatus.READY_FOR_ROUTE); status = DispatchStatus.IN_ROUTE; }
    /** A failed attempt is evidence on the open Delivery; it does not close or replace it. */
    public void recordFailedAttempt() { require(DispatchStatus.IN_ROUTE); }
    public void recordIncident() {
        if (status != DispatchStatus.IN_ROUTE) throw invalid();
        status = DispatchStatus.INCIDENT;
    }
    public void reprogram(DeliveryWindow value, Instant newEta) { require(DispatchStatus.INCIDENT); deliveryWindow = Objects.requireNonNull(value); eta = newEta; status = DispatchStatus.REPROGRAMMED; }
    public void cancel() {
        if (status == DispatchStatus.IN_ROUTE || status == DispatchStatus.PARTIAL || status == DispatchStatus.DELIVERED || status == DispatchStatus.CANCELLED) throw invalid();
        status = DispatchStatus.CANCELLED;
    }
    public void deliverPartially() { require(DispatchStatus.IN_ROUTE); status = DispatchStatus.PARTIAL; }
    public void deliver() { require(DispatchStatus.IN_ROUTE); status = DispatchStatus.DELIVERED; }

    private void transition(DispatchStatus from, DispatchStatus to) { require(from); status = to; }
    private void require(DispatchStatus expected) { if (status != expected) throw invalid(); }
    private DispatchTransitionViolation invalid() { return new DispatchTransitionViolation("Dispatch transition is not allowed from " + status); }
    public UUID id() { return id.value(); } public DispatchOrderId dispatchOrderId() { return id; } public DispatchNumber number() { return number; }
    public InventoryReservationId reservationId() { return reservationId; } public SalesOrderId salesOrderId() { return salesOrderId; }
    public ClientAccountId clientAccountId() { return clientAccountId; } public DestinationSnapshot destination() { return destination; }
    public DispatchStatus status() { return status; } public TransportAssignment assignment() { return assignment; }
    public DeliveryWindow deliveryWindow() { return deliveryWindow; } public Instant eta() { return eta; } public long version() { return version; }
}
