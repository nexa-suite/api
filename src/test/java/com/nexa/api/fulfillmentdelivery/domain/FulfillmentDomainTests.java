package com.nexa.api.fulfillmentdelivery.domain;

import com.nexa.api.fulfillmentdelivery.domain.model.fulfillment.Fulfillment;
import com.nexa.api.fulfillmentdelivery.domain.model.fulfillment.FulfillmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfillmentDomainTests {
    @Test
    void aggregatePreservesQuantityInvariantsAcrossTheCanonicalLifecycle() {
        UUID lineId = UUID.randomUUID();
        Fulfillment fulfillment = Fulfillment.planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new Fulfillment.Line(lineId, UUID.randomUUID(), "CAT-001", new BigDecimal("10"), "UNIT")));

        fulfillment.allocate(Map.of(lineId, new BigDecimal("10")));
        fulfillment.startPicking();
        fulfillment.confirmPicking(Map.of(lineId, new BigDecimal("10")));
        fulfillment.pack();
        fulfillment.stage();
        fulfillment.readyForDispatch();
        fulfillment.handOver();
        fulfillment.recordOutcome(Map.of(lineId, new Fulfillment.Outcome(lineId,
                new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThat(fulfillment.status()).isEqualTo(FulfillmentStatus.COMPLETED);
        assertThat(fulfillment.unresolvedQuantity()).isZero();
        assertThat(fulfillment.version()).isEqualTo(8);
    }

    @Test
    void shortageRemainsExplicitAndCannotExceedAllocation() {
        UUID lineId = UUID.randomUUID();
        Fulfillment fulfillment = Fulfillment.planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new Fulfillment.Line(lineId, UUID.randomUUID(), "CAT-002", new BigDecimal("5"), "BOX")));
        fulfillment.allocate(Map.of(lineId, new BigDecimal("5")));
        fulfillment.startPicking();

        assertThatThrownBy(() -> fulfillment.confirmPicking(Map.of(lineId, new BigDecimal("6"))))
                .isInstanceOf(IllegalArgumentException.class);
        fulfillment.confirmPicking(Map.of(lineId, new BigDecimal("3")));

        assertThat(fulfillment.status()).isEqualTo(FulfillmentStatus.SHORTAGE);
        assertThat(fulfillment.unresolvedQuantity()).isEqualByComparingTo("5");
        assertThatThrownBy(fulfillment::pack).isInstanceOf(IllegalStateException.class);

        fulfillment.resolveShortage(Map.of(lineId, new BigDecimal("2")));
        assertThat(fulfillment.status()).isEqualTo(FulfillmentStatus.PICKED);
        assertThat(fulfillment.unresolvedQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void transitionsRejectMissingLineQuantitiesAndDuplicateLines() {
        UUID lineId = UUID.randomUUID();
        assertThatThrownBy(() -> Fulfillment.planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(
                new Fulfillment.Line(lineId, UUID.randomUUID(), "CAT-003", BigDecimal.ONE, "UNIT"),
                new Fulfillment.Line(lineId, UUID.randomUUID(), "CAT-004", BigDecimal.ONE, "UNIT"))))
                .isInstanceOf(IllegalArgumentException.class);

        Fulfillment fulfillment = Fulfillment.planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(
                new Fulfillment.Line(lineId, UUID.randomUUID(), "CAT-003", BigDecimal.ONE, "UNIT")));
        assertThatThrownBy(() -> fulfillment.allocate(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
