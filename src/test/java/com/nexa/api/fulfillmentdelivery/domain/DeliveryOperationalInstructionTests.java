package com.nexa.api.fulfillmentdelivery.domain;

import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionAcknowledgement;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryOperationalInstruction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryOperationalInstructionTests {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    @Test
    void derivesCriticalityFromAcceptedInstructionKindSet() {
        assertThat(DeliveryInstructionKind.NORMAL.isCritical()).isFalse();
        assertThat(DeliveryInstructionKind.values())
                .filteredOn(DeliveryInstructionKind::isCritical)
                .containsExactly(DeliveryInstructionKind.COLD_CHAIN, DeliveryInstructionKind.ACCESS_RESTRICTION,
                        DeliveryInstructionKind.SPECIAL_UNLOADING, DeliveryInstructionKind.CUSTOMER_SAFETY,
                        DeliveryInstructionKind.GOODS_HANDLING);
    }

    @Test
    void validatesAndTrimsAnImmutableInstructionRevision() {
        DeliveryOperationalInstruction instruction = new DeliveryOperationalInstruction(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), DeliveryInstructionKind.COLD_CHAIN, "  Keep chilled  ",
                1, UUID.randomUUID(), NOW);

        assertThat(instruction.content()).isEqualTo("Keep chilled");
        assertThat(instruction.critical()).isTrue();
        assertThatThrownBy(() -> new DeliveryOperationalInstruction(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), DeliveryInstructionKind.NORMAL, "  ", 1, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalInstructionsCannotRequireCriticalAcknowledgement() {
        assertThatThrownBy(() -> new DeliveryInstructionAcknowledgement(UUID.randomUUID(), 1,
                DeliveryInstructionKind.NORMAL, "Deliver at reception", UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
