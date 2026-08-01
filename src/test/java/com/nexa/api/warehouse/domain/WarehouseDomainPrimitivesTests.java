package com.nexa.api.warehouse.domain;

import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLot;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLotStatus;
import com.nexa.api.warehouse.domain.model.warehouse.StorageZoneStatus;
import com.nexa.api.warehouse.domain.model.warehouse.StorageZoneType;
import com.nexa.api.warehouse.domain.model.warehouse.WarehouseStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarehouseDomainPrimitivesTests {
    @Test
    void inventoryLotOwnsQuantityAndAvailabilityInvariants() {
        InventoryLot lot = InventoryLot.rehydrate("LOT-01", new BigDecimal("12.500"), BigDecimal.ZERO, "KG", InventoryLotStatus.AVAILABLE);
        lot.reserve(new BigDecimal("2.500"));
        lot.adjustOut(new BigDecimal("5"));
        assertThat(lot.onHand()).isEqualByComparingTo("7.500");
        assertThat(lot.available()).isEqualByComparingTo("5.000");
        assertThat(lot.unit()).isEqualTo("KG");
    }

    @Test
    void inventoryLotRejectsOverdrawAndNegativeReservation() {
        InventoryLot lot = InventoryLot.rehydrate("LOT-02", BigDecimal.ONE, BigDecimal.ZERO, "EACH", InventoryLotStatus.AVAILABLE);
        assertThatThrownBy(() -> lot.adjustOut(new BigDecimal("2"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lot.reserve(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalWarehouseVocabularyIsExplicit() {
        assertThat(WarehouseStatus.values()).containsExactly(WarehouseStatus.ACTIVE, WarehouseStatus.SUSPENDED);
        assertThat(StorageZoneStatus.values()).containsExactly(StorageZoneStatus.ACTIVE, StorageZoneStatus.SUSPENDED);
        assertThat(StorageZoneType.values()).containsExactly(StorageZoneType.AMBIENT, StorageZoneType.CHILLED, StorageZoneType.FROZEN, StorageZoneType.QUARANTINE);
    }
}
