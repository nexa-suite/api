package com.nexa.api.logistics.domain;

import com.nexa.api.logistics.domain.handoff.OperationalHandoffNote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalHandoffNoteTests {
    @Test
    void noteIsNormalizedAndCarriesImmutableDispatchVersion() {
        OperationalHandoffNote note = new OperationalHandoffNote(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "  Dock sealed before loading  ", Instant.parse("2026-08-02T12:00:00Z"), 4);

        assertThat(note.note()).isEqualTo("Dock sealed before loading");
        assertThat(note.dispatchVersion()).isEqualTo(4);
    }

    @Test
    void noteRejectsBlankOversizedAndNegativeVersionValues() {
        UUID dispatch = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-02T12:00:00Z");

        assertThatThrownBy(() -> new OperationalHandoffNote(UUID.randomUUID(), dispatch, author, " ", occurredAt, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalHandoffNote(UUID.randomUUID(), dispatch, author, "x".repeat(2001), occurredAt, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationalHandoffNote(UUID.randomUUID(), dispatch, author, "x", occurredAt, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
