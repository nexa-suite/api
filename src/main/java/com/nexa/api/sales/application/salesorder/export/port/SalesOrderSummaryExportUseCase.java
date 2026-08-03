package com.nexa.api.sales.application.salesorder.export.port;

import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface SalesOrderSummaryExportUseCase {
	SalesOrderSummaryExportResult export(CurrentAccessContext context, String orderId,
			SalesOrderSummaryExportFormat format);
}
