package com.nexa.api.catalogcommercialpolicy.domain.model.productfamily;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductFamilyDomainTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    @Test
    void ownsLifecycleAndOptimisticVersion() {
        ProductFamily family = ProductFamily.create(TENANT, WORKSPACE, "FAM-GOUDA", "Queso Gouda Natural",
                "Commercial family", CATEGORY, BRAND, "NL", null, null, "REFRIGERATED", Instant.now());

        assertThat(family.status()).isEqualTo(ProductFamilyStatus.DRAFT);
        family.activate(0);

        assertThat(family.status()).isEqualTo(ProductFamilyStatus.ACTIVE);
        assertThat(family.version()).isEqualTo(1);
        assertThatThrownBy(() -> family.deactivate(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidStorageFamilyAndCountry() {
        assertThatThrownBy(() -> ProductFamily.create(TENANT, WORKSPACE, "FAM", "Family", "Description",
                CATEGORY, BRAND, "NLD", null, null, "REFRIGERATED", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductFamily.create(TENANT, WORKSPACE, "FAM", "Family", "Description",
                CATEGORY, BRAND, "NL", null, null, "ROOM", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
