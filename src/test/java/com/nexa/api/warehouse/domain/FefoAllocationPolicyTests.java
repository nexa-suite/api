package com.nexa.api.warehouse.domain;

import com.nexa.api.warehouse.domain.policy.FefoAllocationPolicy;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLotStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FefoAllocationPolicyTests {
    @Test void allocatesEarliestExpirationThenReceiptThenId() {
        var later=new FefoAllocationPolicy.LotSnapshot("B",new BigDecimal("5"),"UNIT",LocalDate.of(2027,1,1),Instant.parse("2026-01-01T00:00:00Z"));
        var first=new FefoAllocationPolicy.LotSnapshot("A",new BigDecimal("2"),"UNIT",LocalDate.of(2026,12,1),Instant.parse("2026-01-02T00:00:00Z"));
        var result=FefoAllocationPolicy.allocate(List.of(later,first),new BigDecimal("3"),"UNIT");
        assertThat(result.complete()).isTrue(); assertThat(result.allocations()).extracting(FefoAllocationPolicy.Allocation::lotId).containsExactly("A","B"); assertThat(result.allocations().get(0).quantity()).isEqualByComparingTo("2");
    }
    @Test void reportsShortageWithoutPartialCommitDecision() { var result=FefoAllocationPolicy.allocate(List.of(new FefoAllocationPolicy.LotSnapshot("A",new BigDecimal("2"),"UNIT",LocalDate.of(2026,12,1),Instant.now())),new BigDecimal("3"),"UNIT"); assertThat(result.complete()).isFalse(); assertThat(result.shortage()).isEqualByComparingTo("1"); }

    @Test void matchesCommercialUnitCaseToCanonicalInventoryUnit() {
        var result = FefoAllocationPolicy.allocate(List.of(new FefoAllocationPolicy.LotSnapshot(
                "A", BigDecimal.ONE, "UNIT", LocalDate.of(2027, 1, 1), Instant.now())),
                BigDecimal.ONE, "unit");

        assertThat(result.complete()).isTrue();
        assertThat(result.allocations().getFirst().unit()).isEqualTo("UNIT");
    }

    @Test void excludesExpiredBlockedQuarantinedDepletedAndWrongScopeLots() {
        var eligible = new FefoAllocationPolicy.LotSnapshot("eligible", new BigDecimal("4"), "UNIT",
                LocalDate.of(2027, 1, 1), Instant.parse("2026-01-01T00:00:00Z"),
                InventoryLotStatus.AVAILABLE, "SKU-1", "WH-1");
        var expired = new FefoAllocationPolicy.LotSnapshot("expired", new BigDecimal("20"), "UNIT",
                LocalDate.of(2026, 1, 1), Instant.parse("2025-01-01T00:00:00Z"),
                InventoryLotStatus.AVAILABLE, "SKU-1", "WH-1");
        var blocked = new FefoAllocationPolicy.LotSnapshot("blocked", new BigDecimal("20"), "UNIT",
                LocalDate.of(2027, 1, 2), Instant.parse("2026-01-02T00:00:00Z"),
                InventoryLotStatus.BLOCKED, "SKU-1", "WH-1");
        var quarantined = new FefoAllocationPolicy.LotSnapshot("quarantined", new BigDecimal("20"), "UNIT",
                LocalDate.of(2027, 1, 3), Instant.parse("2026-01-03T00:00:00Z"),
                InventoryLotStatus.QUARANTINED, "SKU-1", "WH-1");
        var depleted = new FefoAllocationPolicy.LotSnapshot("depleted", new BigDecimal("20"), "UNIT",
                LocalDate.of(2027, 1, 4), Instant.parse("2026-01-04T00:00:00Z"),
                InventoryLotStatus.DEPLETED, "SKU-1", "WH-1");
        var wrongSku = new FefoAllocationPolicy.LotSnapshot("wrong-sku", new BigDecimal("20"), "UNIT",
                LocalDate.of(2027, 1, 5), Instant.parse("2026-01-05T00:00:00Z"),
                InventoryLotStatus.AVAILABLE, "SKU-2", "WH-1");
        var wrongWarehouse = new FefoAllocationPolicy.LotSnapshot("wrong-warehouse", new BigDecimal("20"), "UNIT",
                LocalDate.of(2027, 1, 6), Instant.parse("2026-01-06T00:00:00Z"),
                InventoryLotStatus.AVAILABLE, "SKU-1", "WH-2");

        var result = FefoAllocationPolicy.allocate(List.of(wrongWarehouse, wrongSku, depleted, quarantined, blocked,
                        expired, eligible), new BigDecimal("3"), "UNIT", "SKU-1", "WH-1", LocalDate.of(2026, 1, 1));

        assertThat(result.complete()).isTrue();
        assertThat(result.allocations()).extracting(FefoAllocationPolicy.Allocation::lotId).containsExactly("eligible");
    }

    @Test void usesStableLotIdTieBreakerAfterExpiryAndReceipt() {
        var second = new FefoAllocationPolicy.LotSnapshot("LOT-2", BigDecimal.ONE, "UNIT",
                LocalDate.of(2027, 1, 1), Instant.parse("2026-01-01T00:00:00Z"));
        var first = new FefoAllocationPolicy.LotSnapshot("LOT-1", BigDecimal.ONE, "UNIT",
                LocalDate.of(2027, 1, 1), Instant.parse("2026-01-01T00:00:00Z"));

        var result = FefoAllocationPolicy.allocate(List.of(second, first), new BigDecimal("2"), "UNIT");

        assertThat(result.allocations()).extracting(FefoAllocationPolicy.Allocation::lotId)
                .containsExactly("LOT-1", "LOT-2");
    }
}
