package com.nexa.api.catalogmanagement.domain.model.brand;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandLifecycleTests {
    private static final UUID BRAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void createsBrandWithNormalizedValues() {
        Brand brand = Brand.create(new BrandId(BRAND_ID), "  acme  ", "  Acme Foods  ", "  Cold chain  ");

        assertThat(brand.id().value()).isEqualTo(BRAND_ID);
        assertThat(brand.slug()).isEqualTo("acme");
        assertThat(brand.name()).isEqualTo("Acme Foods");
        assertThat(brand.description()).isEqualTo("Cold chain");
    }

    @Test
    void supportsIntentSpecificChanges() {
        Brand brand = Brand.create(new BrandId(BRAND_ID), "acme", "Acme", null);

        brand.rename("Acme Fresh");
        brand.changeSlug("acme-fresh");

        assertThat(brand.name()).isEqualTo("Acme Fresh");
        assertThat(brand.slug()).isEqualTo("acme-fresh");
    }

    @Test
    void rejectsBlankAndOverlongValues() {
        assertThatThrownBy(() -> Brand.create(new BrandId(BRAND_ID), " ", "Acme", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Brand.create(new BrandId(BRAND_ID), "acme", "x".repeat(161), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Brand.create(new BrandId(BRAND_ID), "x".repeat(101), "Acme", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
