package com.nexa.api.sales.application.salesorder.model;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record SalesOrderFilter(String status, int page, int size, String sort) {
	public SalesOrderFilter {
		page = Math.max(0, page);
		size = Math.min(100, Math.max(1, size));
		if (status != null && !status.isBlank()) {
			try { com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT)); }
			catch (IllegalArgumentException exception) { throw new SalesInvariantViolation("Sales order status is invalid"); }
			status = status.trim().toUpperCase(java.util.Locale.ROOT);
		}
		sort = normalizeSort(sort);
	}
	private static String normalizeSort(String value) {
		String candidate = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
		String[] parts = candidate.split(",", -1);
		if (parts.length != 2 || !(parts[0].equals("createdAt") || parts[0].equals("updatedAt") || parts[0].equals("number"))
				|| !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) throw new SalesInvariantViolation("Sales order sort is invalid");
		return parts[0] + "," + parts[1].toLowerCase(java.util.Locale.ROOT);
	}
}
