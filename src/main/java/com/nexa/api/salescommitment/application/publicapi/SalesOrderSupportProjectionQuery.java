package com.nexa.api.salescommitment.application.publicapi;

import java.math.BigDecimal;
import java.util.UUID;

/** Minimal Sales Commitment projection for an already-authorized support read. */
@FunctionalInterface
public interface SalesOrderSupportProjectionQuery {
	Snapshot find(UUID tenantId, UUID workspaceId, UUID salesOrderId);

	record Snapshot(String status, BigDecimal total, String currency, long version) { }
}
