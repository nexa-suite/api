package com.nexa.api.sales.application.purchaserequest.model;

import java.time.LocalDate;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record PurchaseRequestFilter(String status, String priority, String search, LocalDate createdFrom,
		LocalDate createdTo, int page, int size, String sort) {
	public PurchaseRequestFilter {
		page = Math.max(0, page);
		size = Math.min(100, Math.max(1, size));
		sort = normalizeSort(sort);
	}

	private static String normalizeSort(String value) {
		String candidate = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
		String[] parts = candidate.split(",", -1);
		if (parts.length != 2 || !(parts[0].equals("createdAt") || parts[0].equals("updatedAt"))
				|| !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) {
			throw new SalesInvariantViolation("Purchase request sort is invalid");
		}
		return parts[0] + "," + parts[1].toLowerCase(java.util.Locale.ROOT);
	}
}
