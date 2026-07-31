package com.nexa.api.warehouse.domain;

import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLot;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLotStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class InventoryLotInvariantTests {
    @Test void reserveAndReleasePreserveAvailableBalance() { var lot=InventoryLot.rehydrate("LOT-1",new BigDecimal("10"),BigDecimal.ZERO,"UNIT",InventoryLotStatus.AVAILABLE); lot.reserve(new BigDecimal("4")); assertThat(lot.available()).isEqualByComparingTo("6"); lot.releaseReservation(new BigDecimal("4")); assertThat(lot.available()).isEqualByComparingTo("10"); }
    @Test void cannotRemoveReservedStock() { var lot=InventoryLot.rehydrate("LOT-1",new BigDecimal("10"),new BigDecimal("8"),"UNIT",InventoryLotStatus.AVAILABLE); assertThatThrownBy(()->lot.adjustOut(new BigDecimal("3"))).isInstanceOf(IllegalStateException.class); }
}
