package com.nexa.api.catalogmanagement.domain.model.brand;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandLifecycleTests {
    @Test
    void createsBrandWithNormalizedValues() {
        Brand brand = Brand.create(new BrandId("brand-1"), "  acme  ", "  Acme Foods  ", "  Cold chain  ");

        assertThat(brand.id().value()).isEqualTo("brand-1");
        assertThat(brand.slug()).isEqualTo("acme");
        assertThat(brand.name()).isEqualTo("Acme Foods");
        assertThat(brand.description()).isEqualTo("Cold chain");
    }

    @Test
    void supportsIntentSpecificChanges() {
        Brand brand = Brand.create(new BrandId("brand-1"), "acme", "Acme", null);

        brand.rename("Acme Fresh");
        brand.changeSlug("acme-fresh");

        assertThat(brand.name()).isEqualTo("Acme Fresh");
        assertThat(brand.slug()).isEqualTo("acme-fresh");
    }

    @Test
    void rejectsBlankAndOverlongValues() {
        assertThatThrownBy(() -> Brand.create(new BrandId("brand-1"), " ", "Acme", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Brand.create(new BrandId("brand-1"), "acme", "x".repeat(161), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Brand.create(new BrandId("brand-1"), "x".repeat(101), "Acme", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
