package com.nexa.api.sales.application.model;

import java.util.List;

public record SalesPage<T>(List<T> items, int page, int size, long total) {
	public SalesPage { items = List.copyOf(items); }
}
