package com.nexa.api.catalogcommercialpolicy.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogCommandIdempotencySupportTests {
    @Test
    void structuredHashIsStableForTheSameCommand() {
        assertThat(CatalogCommandIdempotencySupport.structuredHash("family", "code", "name", null))
                .isEqualTo(CatalogCommandIdempotencySupport.structuredHash("family", "code", "name", null))
                .hasSize(64);
    }

    @Test
    void structuredHashDistinguishesNullFromLiteralMarkers() {
        assertThat(CatalogCommandIdempotencySupport.structuredHash("family", "code", "name", null))
                .isNotEqualTo(CatalogCommandIdempotencySupport.structuredHash("family", "code", "name", "<null>"));
    }

    @Test
    void structuredHashDistinguishesEmbeddedSeparatorsAndFieldBoundaries() {
        assertThat(CatalogCommandIdempotencySupport.structuredHash("a\u001fb", "c"))
                .isNotEqualTo(CatalogCommandIdempotencySupport.structuredHash("a", "b\u001fc"));
        assertThat(CatalogCommandIdempotencySupport.structuredHash("ab", "c"))
                .isNotEqualTo(CatalogCommandIdempotencySupport.structuredHash("a", "bc"));
    }
}
