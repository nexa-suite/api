package com.nexa.api.salescommitment.application.salesorder.model;

import org.springframework.modulith.NamedInterface;
import com.nexa.api.salescommitment.domain.publicapi.SalesInvariantViolation;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderStatus;

@NamedInterface("sales-public")
public record SalesOrderFilter(String status, String priority, String clientAccountId, String search,
		LocalDate createdFrom, LocalDate createdTo, LocalDate requestedDeliveryFrom, LocalDate requestedDeliveryTo,
		int page, int size, String sort, List<String> statuses) {
	public SalesOrderFilter(String status, String priority, String clientAccountId, String search,
			LocalDate createdFrom, LocalDate createdTo, LocalDate requestedDeliveryFrom, LocalDate requestedDeliveryTo,
			int page, int size, String sort) {
		this(status, priority, clientAccountId, search, createdFrom, createdTo, requestedDeliveryFrom,
				requestedDeliveryTo, page, size, sort, List.of());
	}
	public SalesOrderFilter(String status, int page, int size, String sort) {
		this(status, null, null, null, null, null, null, null, page, size, sort);
	}
	public SalesOrderFilter {
		page = Math.max(0, page);
		size = Math.min(100, Math.max(1, size));
		if (status != null && !status.isBlank()) {
			status = normalizeStatus(status);
		}
		if (statuses == null) statuses = List.of();
		if (statuses.size() > SalesOrderStatus.values().length) throw new SalesInvariantViolation("Too many sales order statuses");
		statuses = statuses.stream().map(SalesOrderFilter::normalizeStatus).distinct().toList();
		if (status != null && !statuses.isEmpty()) throw new SalesInvariantViolation("Use status or statuses, not both");
		if (priority != null && !priority.isBlank()) priority = PurchaseRequestPriority.from(priority).name();
		if (search != null && search.length() > 160) throw new SalesInvariantViolation("Sales order search is too long");
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) throw new SalesInvariantViolation("Sales order created date range is invalid");
		if (requestedDeliveryFrom != null && requestedDeliveryTo != null && requestedDeliveryFrom.isAfter(requestedDeliveryTo)) throw new SalesInvariantViolation("Sales order delivery date range is invalid");
		sort = normalizeSort(sort);
	}
	private static String normalizeStatus(String value) {
		if (value == null || value.isBlank()) throw new SalesInvariantViolation("Sales order status is invalid");
		try { return SalesOrderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name(); }
		catch (IllegalArgumentException exception) { throw new SalesInvariantViolation("Sales order status is invalid"); }
	}
	private static String normalizeSort(String value) {
		String candidate = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
		String[] parts = candidate.split(",", -1);
		if (parts.length != 2 || !(parts[0].equals("createdAt") || parts[0].equals("updatedAt") || parts[0].equals("orderNumber") || parts[0].equals("priority") || parts[0].equals("total") || parts[0].equals("requestedDeliveryDate"))
				|| !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) throw new SalesInvariantViolation("Sales order sort is invalid");
		return parts[0] + "," + parts[1].toLowerCase(java.util.Locale.ROOT);
	}
}
