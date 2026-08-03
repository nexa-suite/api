package com.nexa.api.sales.application.salesorder.export.model;

import java.util.Arrays;

public record SalesOrderSummaryExportResult(String filename, String contentType, byte[] content) {
	public SalesOrderSummaryExportResult {
		content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
	}

	@Override
	public byte[] content() { return Arrays.copyOf(content, content.length); }
}
