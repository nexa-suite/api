package com.nexa.api.customerrelationships.application.clientaccount.model;

import java.util.List;

public record CustomerAccountPage<T>(List<T> items, int page, int size, long total) {
    public CustomerAccountPage {
        items = List.copyOf(items);
    }
}
