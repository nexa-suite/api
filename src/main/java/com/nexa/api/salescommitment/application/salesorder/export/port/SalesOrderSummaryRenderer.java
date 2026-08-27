package com.nexa.api.salescommitment.application.salesorder.export.port;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummarySnapshot;

public interface SalesOrderSummaryRenderer {
	SalesOrderSummaryExportFormat format();
	byte[] render(SalesOrderSummarySnapshot snapshot);
}
