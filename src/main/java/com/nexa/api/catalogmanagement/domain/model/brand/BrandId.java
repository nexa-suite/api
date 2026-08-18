package com.nexa.api.catalogmanagement.domain.model.brand;

import java.util.UUID;

public record BrandId(UUID value) {
    public BrandId {
        if (value == null) throw new IllegalArgumentException("Brand id is required");
    }
}
