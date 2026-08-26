package com.nexa.api.inventoryavailability.domain;

import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLot;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class InventoryLotInvariantTests {
    @Test void reserveAndReleasePreserveAvailableBalance() { var lot=InventoryLot.rehydrate("LOT-1",new BigDecimal("10"),BigDecimal.ZERO,"UNIT",InventoryLotStatus.AVAILABLE); lot.reserve(new BigDecimal("4")); assertThat(lot.available()).isEqualByComparingTo("6"); lot.releaseReservation(new BigDecimal("4")); assertThat(lot.available()).isEqualByComparingTo("10"); }
    @Test void cannotRemoveReservedStock() { var lot=InventoryLot.rehydrate("LOT-1",new BigDecimal("10"),new BigDecimal("8"),"UNIT",InventoryLotStatus.AVAILABLE); assertThatThrownBy(()->lot.adjustOut(new BigDecimal("3"))).isInstanceOf(IllegalStateException.class); }

    @Test void reservedLotCannotBeBlockedOrQuarantined() {
        var lot = InventoryLot.rehydrate("LOT-2", new BigDecimal("10"), new BigDecimal("2"), "UNIT", InventoryLotStatus.AVAILABLE);

        assertThatThrownBy(lot::markBlocked).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(lot::markQuarantined).isInstanceOf(IllegalStateException.class);
    }

    @Test void consumingReservedStockCannotGoNegativeAndDepletesAtZero() {
        var lot = InventoryLot.rehydrate("LOT-3", new BigDecimal("5"), new BigDecimal("5"), "UNIT", InventoryLotStatus.AVAILABLE);

        lot.consume(new BigDecimal("5"));

        assertThat(lot.onHand()).isZero();
        assertThat(lot.reserved()).isZero();
        assertThat(lot.status()).isEqualTo(InventoryLotStatus.DEPLETED);
        assertThatThrownBy(() -> lot.consume(BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test void blockedLotCanBeRestoredOnlyWithPhysicalStock() {
        var lot = InventoryLot.rehydrate("LOT-4", new BigDecimal("3"), BigDecimal.ZERO, "unit", InventoryLotStatus.AVAILABLE);
        lot.markBlocked();
        lot.restoreAvailability();

        assertThat(lot.status()).isEqualTo(InventoryLotStatus.AVAILABLE);
        assertThat(lot.unit()).isEqualTo("UNIT");
    }
}
