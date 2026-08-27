package com.nexa.api.salescommitment.application.salesorder.export.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable, tenant-scoped export source assembled from persisted order snapshots. */
public record SalesOrderSummarySnapshot(String id, String number, String tenantId, String workspaceId,
		String clientAccountId, String priority, LocalDate requestedDeliveryDate, String deliverySnapshot,
		String paymentOption, String notes, String currency, BigDecimal total, String status, Instant createdAt,
		List<SalesOrderSummaryLineSnapshot> lines) {
	public SalesOrderSummarySnapshot {
		lines = lines == null ? List.of() : List.copyOf(lines);
	}
}
