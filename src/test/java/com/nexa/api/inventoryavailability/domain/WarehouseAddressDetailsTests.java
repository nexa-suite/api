package com.nexa.api.inventoryavailability.domain;

import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseAddressDetails;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarehouseAddressDetailsTests {
    @Test
    void normalizesExplicitFieldsWithoutInventingUnknownValues() {
        var details = new WarehouseAddressDetails(" pe ", "15", "1501", "150101", " Lima ", "CALLE", "Test", "100", null, null, null);
        assertThat(details.countryCode()).isEqualTo("PE");
        assertThat(details.city()).isEqualTo("Lima");
        assertThat(details.postalCode()).isNull();
    }

    @Test
    void rejectsInconsistentPeruHierarchy() {
        assertThatThrownBy(() -> new WarehouseAddressDetails("PE", "15", "1501", "080101", "Lima", "CALLE", "Test", "100", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
