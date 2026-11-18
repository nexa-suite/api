package com.nexa.api.tenantaccessgovernance.iam.legalacceptance;

import com.nexa.api.tenantaccessgovernance.iam.application.service.LegalAcceptance;
import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.LegalAcceptanceStore;
import com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.JdbcLegalAcceptanceStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.LegalDocumentChangedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalAcceptanceTests {
    @Test void documentVersionHasStableContentDigest() {
        assertThat(LegalAcceptance.DIGEST).isEqualTo("91c37e43fb15f9461e4a7af14003c551114a0a929379ab91212f5286ab5eab26");
    }

    @Test void acceptanceIsPerIdentityAndIdempotent() {
        var store = new MemoryStore();
        var service = new LegalAcceptance(store);
        var user = UUID.randomUUID();
        var request = new LegalAcceptanceModels.Request(LegalAcceptance.DOCUMENT_ID, LegalAcceptance.VERSION, LegalAcceptance.DIGEST);
        assertThat(service.current(user).accepted()).isFalse();
        var first = service.accept(user, request);
        assertThat(first.accepted()).isTrue();
        assertThat(first.acceptedAt()).isEqualTo(Instant.EPOCH);
        assertThat(service.accept(user, request)).isEqualTo(first);
        assertThat(service.current(UUID.randomUUID()).accepted()).isFalse();
    }

    @Test void staleOrChangedContentIsRejectedWithoutWriting() {
        var store = new MemoryStore();
        var service = new LegalAcceptance(store);
        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), new LegalAcceptanceModels.Request("mobile-terms", "old", LegalAcceptance.DIGEST)))
                .isInstanceOf(LegalDocumentChangedException.class);
        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), new LegalAcceptanceModels.Request("mobile-terms", "1", "wrong")))
                .isInstanceOf(LegalDocumentChangedException.class);
        assertThat(store.rows).isEmpty();
    }

    @Test void sameVersionWithDifferentStoredDigestFailsClosed() {
        var store = new MemoryStore();
        var user = UUID.randomUUID();
        store.rows.put(user, new LegalAcceptanceModels.Evidence("different", Instant.EPOCH));
        assertThatThrownBy(() -> new LegalAcceptance(store).current(user)).isInstanceOf(IllegalStateException.class);
    }

    private static class MemoryStore implements LegalAcceptanceStore {
        final Map<UUID, LegalAcceptanceModels.Evidence> rows = new HashMap<>();
        public Optional<LegalAcceptanceModels.Evidence> find(UUID user, String document, String version) { return Optional.ofNullable(rows.get(user)); }
        public void insertIfAbsent(UUID user, String document, String version, String digest) {
            rows.putIfAbsent(user, new LegalAcceptanceModels.Evidence(digest, Instant.EPOCH));
        }
    }
}
