package com.nexa.api.catalogmanagement.domain.model.category;

import java.util.UUID;

public record CategoryId(UUID value) {
    public CategoryId {
        if (value == null) throw new IllegalArgumentException("Category id is required");
    }
}
