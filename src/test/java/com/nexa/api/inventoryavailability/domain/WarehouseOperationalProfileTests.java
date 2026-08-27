package com.nexa.api.inventoryavailability.domain;

import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseBuyerProjection;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseHours;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseInternalSnapshot;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseLocation;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseOperationalProfile;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseProfile;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseSelectionPolicy;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseServiceability;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarehouseOperationalProfileTests {
    @Test
    void internalSnapshotRetainsPolicyWhileBuyerProjectionStaysNarrow() {
        WarehouseOperationalProfile profile = new WarehouseOperationalProfile(
                new WarehouseProfile(UUID.randomUUID(), "WH-01", "Cold Hub",
                        new WarehouseLocation("Av. Frio 100"), WarehouseStatus.SUSPENDED, 7),
                new WarehouseHours(LocalTime.of(8, 0), LocalTime.of(18, 0)),
                new WarehouseServiceability(false), WarehouseSelectionPolicy.PREFERRED, 11);

        WarehouseInternalSnapshot internal = profile.internalSnapshot();
        WarehouseBuyerProjection buyer = profile.buyerProjection();

        assertThat(internal.status()).isEqualTo(WarehouseStatus.SUSPENDED);
        assertThat(internal.selectionPolicy()).isEqualTo(WarehouseSelectionPolicy.PREFERRED);
        assertThat(internal.settingsVersion()).isEqualTo(11);
        assertThat(buyer.serviceable()).isFalse();
        assertThat(buyer.code()).isEqualTo("WH-01");
        assertThat(buyer.hours().startsAt()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void operationalHoursAndVersionsAreValidatedByTheDomain() {
        assertThatThrownBy(() -> new WarehouseHours(LocalTime.NOON, LocalTime.NOON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WarehouseProfile(UUID.randomUUID(), "WH-01", "Cold Hub",
                new WarehouseLocation(null), WarehouseStatus.ACTIVE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
