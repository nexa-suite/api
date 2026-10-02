package com.nexa.api.fulfillmentdelivery.domain.load;

import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator.DeliveryFacts;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator.Evaluation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoadCompatibilityEvaluatorTests {
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Test
    void acceptsSameWarehouseAndTemperatureWithOverlappingWindowsWithoutLegacyDispatchOrder() {
        DeliveryFacts first = facts(Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T14:00:00Z"), null);
        DeliveryFacts second = facts(Instant.parse("2026-10-01T10:00:00Z"),
                Instant.parse("2026-10-01T16:00:00Z"), null);

        Evaluation result = LoadCompatibilityEvaluator.evaluate(List.of(first, second));

        assertThat(result.compatible()).isTrue();
        assertThat(result.originWarehouseId()).isEqualTo(WAREHOUSE);
        assertThat(result.commonWindowStart()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(result.commonWindowEnd()).isEqualTo(Instant.parse("2026-10-01T14:00:00Z"));
    }

    @Test
    void pairedNoSkuTemperatureBoundsAreExplicitNoneAndCannotMixWithABoundedRequirement() {
        DeliveryFacts noRequirement = new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1,
                "READY_FOR_DISPATCH", null, WAREHOUSE, Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T14:00:00Z"), null, null, "NONE", "UNKNOWN", null,
                false, false, false, false, false);
        DeliveryFacts secondNoRequirement = new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1,
                "READY_FOR_DISPATCH", null, WAREHOUSE, Instant.parse("2026-10-01T09:00:00Z"),
                Instant.parse("2026-10-01T15:00:00Z"), null, null, "none", "UNKNOWN", null,
                false, false, false, false, false);

        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(noRequirement, secondNoRequirement)).compatible()).isTrue();
        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(noRequirement,
                facts(Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"), null)))
                .reason()).isEqualTo("LOAD_TEMPERATURE_REQUIREMENTS_MISMATCH");
    }

    @Test
    void rejectsMissingWindowAndMismatchedWarehouseOrTemperature() {
        DeliveryFacts first = facts(Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T14:00:00Z"), null);

        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(first,
                facts(null, Instant.parse("2026-10-01T16:00:00Z"), null))).reason())
                .isEqualTo("LOAD_COMPATIBILITY_FACTS_UNAVAILABLE");
        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(first,
                facts(Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"),
                        UUID.randomUUID()))).reason()).isEqualTo("LOAD_ORIGIN_WAREHOUSE_MISMATCH");
        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(first,
                new DeliveryFacts(UUID.randomUUID(), 1, UUID.randomUUID(), 1, "READY_FOR_DISPATCH", null,
                        WAREHOUSE, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"),
                        new BigDecimal("-4"), BigDecimal.ZERO, "CELSIUS", "UNKNOWN", null,
                        false, false, false, false, false))).reason())
                .isEqualTo("LOAD_TEMPERATURE_REQUIREMENTS_MISMATCH");
    }

    @Test
    void rejectsFulfillmentOrDeliveryThatIsNotEligibleForAPlanningLoad() {
        DeliveryFacts eligible = facts(Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T14:00:00Z"), null);

        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(eligible,
                new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1, "PARTIAL", null,
                        WAREHOUSE, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"),
                        new BigDecimal("-5"), BigDecimal.ZERO, "CELSIUS", "UNKNOWN", null,
                        false, false, false, false, false))).reason()).isEqualTo("DELIVERY_NOT_READY_FOR_LOAD");
        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(eligible,
                new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1, "READY_FOR_DISPATCH", "CLOSED",
                        WAREHOUSE, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"),
                        new BigDecimal("-5"), BigDecimal.ZERO, "CELSIUS", "UNKNOWN", null,
                        false, false, false, false, false))).reason()).isEqualTo("DELIVERY_NOT_READY_FOR_LOAD");
        assertThat(LoadCompatibilityEvaluator.evaluate(List.of(eligible,
                new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1, "READY_FOR_DISPATCH", null,
                        WAREHOUSE, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T14:00:00Z"),
                        new BigDecimal("-5"), BigDecimal.ZERO, "CELSIUS", "UNKNOWN", null,
                        false, true, false, false, false))).reason()).isEqualTo("DELIVERY_OPERATIONAL_HOLD");
    }

    private static DeliveryFacts facts(Instant windowStart, Instant windowEnd, UUID warehouseId) {
        return new DeliveryFacts(UUID.randomUUID(), 0, UUID.randomUUID(), 1, "READY_FOR_DISPATCH", null,
                warehouseId == null ? WAREHOUSE : warehouseId, windowStart, windowEnd,
                new BigDecimal("-5"), BigDecimal.ZERO, "CELSIUS", "UNKNOWN", null,
                false, false, false, false, false);
    }
}
