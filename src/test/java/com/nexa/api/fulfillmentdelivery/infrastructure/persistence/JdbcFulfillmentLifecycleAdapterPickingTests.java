package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort.PickedLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFulfillmentLifecycleAdapterPickingTests {
    private static final UUID LINE_ONE = UUID.randomUUID();
    private static final UUID LINE_TWO = UUID.randomUUID();
    private static final UUID PHYSICAL_LINE = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID SKU = UUID.randomUUID();

    @Test
    void acceptsFullyLegacyPickingForBackwardCompatibility() {
        assertThat(JdbcFulfillmentLifecycleAdapter.isMixedPickingMode(List.of(
                new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT"),
                new PickedLine(LINE_TWO, SKU, BigDecimal.ONE, "UNIT"))))
                .isFalse();
    }

    @Test
    void rejectsMixedLegacyAndPhysicalPickingMode() {
        assertThat(JdbcFulfillmentLifecycleAdapter.isMixedPickingMode(List.of(
                new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT"),
                new PickedLine(LINE_TWO, SKU, BigDecimal.ONE, "UNIT", PHYSICAL_LINE, LOT, WAREHOUSE, false, null))))
                .isTrue();
    }

    @Test
    void rejectsDuplicateLegacyLinesBeforeTheAppendOnlyUniqueIndex() {
        assertThat(JdbcFulfillmentLifecycleAdapter.containsDuplicateLegacyLines(Map.of(
                LINE_ONE, List.of(
                        new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT"),
                        new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT")),
                LINE_TWO, List.of(new PickedLine(LINE_TWO, SKU, BigDecimal.ONE, "UNIT")))))
                .isTrue();
    }

    @Test
    void permitsMultiplePhysicalLinesForOneFulfillmentLine() {
        assertThat(JdbcFulfillmentLifecycleAdapter.containsDuplicateLegacyLines(Map.of(
                LINE_ONE, List.of(
                        new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT", PHYSICAL_LINE, LOT, WAREHOUSE, false, null),
                        new PickedLine(LINE_ONE, SKU, BigDecimal.ONE, "UNIT", UUID.randomUUID(), LOT, WAREHOUSE, false, null)))))
                .isFalse();
    }
}
