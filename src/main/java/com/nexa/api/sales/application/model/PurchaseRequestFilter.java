package com.nexa.api.sales.application.model;

import java.time.LocalDate;

public record PurchaseRequestFilter(String status, String priority, String search, LocalDate createdFrom,
		LocalDate createdTo, int page, int size, String sort) {
	public PurchaseRequestFilter { page = Math.max(0, page); size = Math.min(100, Math.max(1, size)); sort = sort == null || sort.isBlank() ? "createdAt,desc" : sort; }
}
