package com.nexa.api.warehouse.domain;

import com.nexa.api.warehouse.domain.model.inventoryreservation.InventoryReservation;
import com.nexa.api.warehouse.domain.model.inventoryreservation.InventoryReservationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryReservationInvariantTests {
    @Test void reservationTransitionsAreExplicitAndOneWay() {
        var reservation = InventoryReservation.rehydrate("RES-1", InventoryReservationStatus.PENDING,
                List.of(new InventoryReservation.Allocation("LOT-1", BigDecimal.ONE)));

        reservation.reserve(reservation.allocations());
        reservation.release();

        assertThat(reservation.status()).isEqualTo(InventoryReservationStatus.RELEASED);
        assertThatThrownBy(reservation::expire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reservation::consume).isInstanceOf(IllegalStateException.class);
    }

    @Test void shortageCannotBePromotedToReservedWithoutAFullAllocationSet() {
        var reservation = InventoryReservation.rehydrate("RES-2", InventoryReservationStatus.PENDING, List.of());

        reservation.recordShortage();

        assertThat(reservation.status()).isEqualTo(InventoryReservationStatus.SHORTAGE);
        assertThatThrownBy(() -> reservation.reserve(List.of(new InventoryReservation.Allocation("LOT-1", BigDecimal.ONE))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void duplicateLotAllocationsAreRejected() {
        assertThatThrownBy(() -> InventoryReservation.rehydrate("RES-3", InventoryReservationStatus.PENDING,
                List.of(new InventoryReservation.Allocation("LOT-1", BigDecimal.ONE),
                        new InventoryReservation.Allocation("LOT-1", BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
