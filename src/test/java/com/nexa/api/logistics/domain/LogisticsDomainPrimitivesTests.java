package com.nexa.api.logistics.domain;

import com.nexa.api.logistics.domain.dispatchorder.ClientAccountId;
import com.nexa.api.logistics.domain.dispatchorder.DeliveryWindow;
import com.nexa.api.logistics.domain.dispatchorder.DispatchNumber;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import com.nexa.api.logistics.domain.dispatchorder.DestinationSnapshot;
import com.nexa.api.logistics.domain.dispatchorder.InventoryReservationId;
import com.nexa.api.logistics.domain.dispatchorder.SalesOrderId;
import com.nexa.api.logistics.domain.dispatchorder.TransportAssignment;
import com.nexa.api.logistics.domain.delivery.DeliveryAttempt;
import com.nexa.api.logistics.domain.delivery.DeliveryAttemptLine;
import com.nexa.api.logistics.domain.delivery.DeliveryAttemptStatus;
import com.nexa.api.logistics.domain.temperaturereading.TemperatureScale;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticsDomainPrimitivesTests {
    private static final Instant START = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    void dispatchAggregateOwnsTheCanonicalLifecycle() {
        DispatchOrder dispatch = DispatchOrder.create(UUID.randomUUID(), new DispatchNumber("DO-2026-000001"), new InventoryReservationId(UUID.randomUUID()), new SalesOrderId(UUID.randomUUID()), new ClientAccountId(UUID.randomUUID()), new DestinationSnapshot("Lima"));
        dispatch.startPreparation();
        dispatch.assign(new TransportAssignment(UUID.randomUUID(), "Driver", "TRUCK-1", "Route 1"));
        dispatch.schedule(new DeliveryWindow(START, START.plus(2, java.time.temporal.ChronoUnit.HOURS)), START.plus(60, java.time.temporal.ChronoUnit.MINUTES));
        dispatch.markReadyForRoute();
        dispatch.startRoute();
        dispatch.deliver();
        assertThat(dispatch.status()).isEqualTo(DispatchStatus.DELIVERED);
    }

    @Test
    void lifecycleRejectsInvalidTransitions() {
        DispatchOrder dispatch = DispatchOrder.create(UUID.randomUUID(), new DispatchNumber("DO-2026-000002"), new InventoryReservationId(UUID.randomUUID()), new SalesOrderId(UUID.randomUUID()), new ClientAccountId(UUID.randomUUID()), new DestinationSnapshot("Lima"));
        assertThatThrownBy(() -> dispatch.startRoute()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DeliveryWindow(START, START)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedAttemptKeepsTheSameDeliveryOpenAndPartialClosesIt() {
        DispatchOrder dispatch = routedDispatch();

        dispatch.recordFailedAttempt();
        assertThat(dispatch.status()).isEqualTo(DispatchStatus.IN_ROUTE);

        DeliveryAttempt failed = new DeliveryAttempt(UUID.randomUUID(), dispatch.id(), 1,
                DeliveryAttemptStatus.FAILED, "Buyer unavailable", START, java.util.List.of());
        assertThat(failed.status()).isEqualTo(DeliveryAttemptStatus.FAILED);

        dispatch.deliverPartially();
        assertThat(dispatch.status()).isEqualTo(DispatchStatus.PARTIAL);
        assertThatThrownBy(dispatch::deliver).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialAttemptRequiresDeliveredLinesAndFailedReason() {
        assertThatThrownBy(() -> new DeliveryAttempt(UUID.randomUUID(), UUID.randomUUID(), 1,
                DeliveryAttemptStatus.FAILED, " ", START, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryAttempt(UUID.randomUUID(), UUID.randomUUID(), 1,
                DeliveryAttemptStatus.PARTIAL, null, START, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DeliveryAttempt(UUID.randomUUID(), UUID.randomUUID(), 1,
                DeliveryAttemptStatus.PARTIAL, null, START,
                java.util.List.of(new DeliveryAttemptLine("CAT-0002", new BigDecimal("0.5"), "UNIT")))
                .lines()).hasSize(1);
    }

    @Test
    void temperatureScaleConvertsExplicitlyAndBoundsValues() {
        assertThat(TemperatureScale.CELSIUS.toCelsius(new BigDecimal("-18.250"))).isEqualByComparingTo("-18.250");
        assertThat(TemperatureScale.FAHRENHEIT.toCelsius(new BigDecimal("32"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> TemperatureScale.CELSIUS.toCelsius(new BigDecimal("1000"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static DispatchOrder routedDispatch() {
        DispatchOrder dispatch = DispatchOrder.create(UUID.randomUUID(), new DispatchNumber("DO-2026-000003"),
                new InventoryReservationId(UUID.randomUUID()), new SalesOrderId(UUID.randomUUID()),
                new ClientAccountId(UUID.randomUUID()), new DestinationSnapshot("Lima"));
        dispatch.startPreparation();
        dispatch.assign(new TransportAssignment(UUID.randomUUID(), "Driver", "TRUCK-1", "Route 1"));
        dispatch.schedule(new DeliveryWindow(START, START.plus(2, java.time.temporal.ChronoUnit.HOURS)),
                START.plus(60, java.time.temporal.ChronoUnit.MINUTES));
        dispatch.markReadyForRoute();
        dispatch.startRoute();
        return dispatch;
    }
}
