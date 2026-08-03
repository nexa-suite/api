package com.nexa.api.sales.application.salesorder.export.port;

import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummarySnapshot;

import java.util.Optional;

public interface SalesOrderSummaryProjectionPort {
	Optional<SalesOrderSummarySnapshot> find(String tenantId, String workspaceId, String clientAccountId, String orderId);
}
