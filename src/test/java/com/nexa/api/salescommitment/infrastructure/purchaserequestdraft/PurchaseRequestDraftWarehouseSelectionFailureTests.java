package com.nexa.api.salescommitment.infrastructure.purchaserequestdraft;

import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationException;
import com.nexa.api.salescommitment.application.exception.PurchaseRequestDraftWarehouseSelectionUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseRequestDraftWarehouseSelectionFailureTests {
    @Test
    void reportsShortageOnlyWhenAggregateSellableAvailabilityIsBelowDemand() {
        UUID sku = UUID.randomUUID();

        RuntimeException shortage = PurchaseRequestDraftService.warehouseSelectionFailure(
                Map.of(sku, new BigDecimal("3")), Map.of(sku, new BigDecimal("2")));
        assertThat(shortage).isInstanceOf(WarehouseOperationException.class);
        assertThat(((WarehouseOperationException) shortage).code())
                .isEqualTo("INSUFFICIENT_SELLABLE_AVAILABILITY");

        RuntimeException splitWarehouseSupply = PurchaseRequestDraftService.warehouseSelectionFailure(
                Map.of(sku, new BigDecimal("3")), Map.of(sku, new BigDecimal("3")));
        assertThat(splitWarehouseSupply).isInstanceOf(PurchaseRequestDraftWarehouseSelectionUnavailableException.class);
    }
}
