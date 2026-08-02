package com.nexa.api.catalogmanagement.domain.model.pricing;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;

import java.util.Objects;
import java.util.UUID;

public record Price(UUID id, Money money, PricePeriod period, String sourceCode) {
    public Price {
        id = Objects.requireNonNull(id, "Price id is required");
        money = Objects.requireNonNull(money, "Price money is required");
        period = Objects.requireNonNull(period, "Price period is required");
        if (sourceCode != null && sourceCode.length() > 80) throw new IllegalArgumentException("Price source code is too long");
    }
}
