package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import java.time.Instant;
import java.util.Objects;

public record PricePeriod(Instant validFrom, Instant validUntil) {
    public PricePeriod {
        validFrom = Objects.requireNonNull(validFrom, "Price start is required");
        if (validUntil != null && !validUntil.isAfter(validFrom)) throw new IllegalArgumentException("Price validity period is invalid");
    }

    public boolean contains(Instant value) {
        Objects.requireNonNull(value, "Price instant is required");
        return !value.isBefore(validFrom) && (validUntil == null || value.isBefore(validUntil));
    }
    public boolean overlaps(PricePeriod other) {
        Objects.requireNonNull(other, "Price period is required");
        return (validUntil == null || other.validFrom().isBefore(validUntil))
                && (other.validUntil() == null || validFrom.isBefore(other.validUntil()));
    }
}
