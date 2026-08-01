package com.nexa.api.warehouse.domain;

import com.nexa.api.warehouse.domain.policy.FefoAllocationPolicy;
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
}
