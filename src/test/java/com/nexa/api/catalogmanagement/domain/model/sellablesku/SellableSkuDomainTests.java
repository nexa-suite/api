package com.nexa.api.catalogmanagement.domain.model.sellablesku;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellableSkuDomainTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();

    @Test
    void ownsPresentationLifecycleAndVisibility() {
        SellableSku sku = SellableSku.create(TENANT, WORKSPACE, FAMILY, "SKU-GOUDA-CORTE", null,
                "CORTE", "VACUUM", "UNIT", new BigDecimal("0.150"), new BigDecimal("0.170"),
                BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("8"), 30, 5, true, true,
                "STANDARD", Instant.now());

        sku.activate(0);
        assertThat(sku.status()).isEqualTo(SellableSkuStatus.ACTIVE);
        assertThat(sku.visible()).isTrue();
        assertThat(sku.version()).isEqualTo(1);

        sku.discontinue(1);
        assertThat(sku.status()).isEqualTo(SellableSkuStatus.DISCONTINUED);
        assertThat(sku.visible()).isFalse();
    }

    @Test
    void rejectsInvalidTemperatureAndGtin() {
        assertThatThrownBy(() -> SellableSku.create(TENANT, WORKSPACE, FAMILY, "SKU", "1234",
                "CORTE", "VACUUM", "UNIT", null, null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                30, 0, true, true, "STANDARD", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SellableSku.create(TENANT, WORKSPACE, FAMILY, "SKU", null,
                "CORTE", "VACUUM", "UNIT", null, null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                30, 0, true, true, "STANDARD", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
