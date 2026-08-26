package com.nexa.api.catalogcommercialpolicy.domain.model.brand;

import java.util.UUID;

public record BrandId(UUID value) {
    public BrandId {
        if (value == null) throw new IllegalArgumentException("Brand id is required");
    }
}
