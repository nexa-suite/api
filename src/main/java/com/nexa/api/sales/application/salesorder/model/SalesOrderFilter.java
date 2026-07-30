package com.nexa.api.sales.application.salesorder.model;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;

import java.time.LocalDate;

public record SalesOrderFilter(String status, String priority, String clientAccountId, String search,
		LocalDate createdFrom, LocalDate createdTo, LocalDate requestedDeliveryFrom, LocalDate requestedDeliveryTo,
		int page, int size, String sort) {
	public SalesOrderFilter(String status, int page, int size, String sort) {
		this(status, null, null, null, null, null, null, null, page, size, sort);
	}
	public SalesOrderFilter {
		page = Math.max(0, page);
		size = Math.min(100, Math.max(1, size));
		if (status != null && !status.isBlank()) {
			try { com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT)); }
			catch (IllegalArgumentException exception) { throw new SalesInvariantViolation("Sales order status is invalid"); }
			status = status.trim().toUpperCase(java.util.Locale.ROOT);
		}
		if (priority != null && !priority.isBlank()) priority = PurchaseRequestPriority.from(priority).name();
		if (search != null && search.length() > 160) throw new SalesInvariantViolation("Sales order search is too long");
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) throw new SalesInvariantViolation("Sales order created date range is invalid");
		if (requestedDeliveryFrom != null && requestedDeliveryTo != null && requestedDeliveryFrom.isAfter(requestedDeliveryTo)) throw new SalesInvariantViolation("Sales order delivery date range is invalid");
		sort = normalizeSort(sort);
	}
	private static String normalizeSort(String value) {
		String candidate = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
		String[] parts = candidate.split(",", -1);
		if (parts.length != 2 || !(parts[0].equals("createdAt") || parts[0].equals("updatedAt") || parts[0].equals("orderNumber") || parts[0].equals("priority") || parts[0].equals("total") || parts[0].equals("requestedDeliveryDate"))
				|| !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) throw new SalesInvariantViolation("Sales order sort is invalid");
		return parts[0] + "," + parts[1].toLowerCase(java.util.Locale.ROOT);
	}
}
