package com.nexa.api.sales.application.salesorder.export.model;

import java.util.Locale;

public enum SalesOrderSummaryExportFormat {
	PDF("application/pdf", "pdf"),
	CSV("text/csv;charset=UTF-8", "csv");

	private final String contentType;
	private final String extension;

	SalesOrderSummaryExportFormat(String contentType, String extension) {
		this.contentType = contentType;
		this.extension = extension;
	}

	public String contentType() { return contentType; }
	public String extension() { return extension; }

	public static SalesOrderSummaryExportFormat from(String value) {
		if (value == null || value.isBlank()) return PDF;
		return valueOf(value.strip().toUpperCase(Locale.ROOT));
	}
}
