package com.nexa.api.salescommitment.application.model;

import org.springframework.modulith.NamedInterface;

import java.util.List;

@NamedInterface("sales-public")
public record SalesPage<T>(List<T> items, int page, int size, long total) {
	public SalesPage { items = List.copyOf(items); }
}
