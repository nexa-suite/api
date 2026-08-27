package com.nexa.api.salescommitment.application.salesorder.export.service;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SalesOrderSummaryRendererStrategy {
	private final Map<SalesOrderSummaryExportFormat, SalesOrderSummaryRenderer> renderers;

	public SalesOrderSummaryRendererStrategy(List<SalesOrderSummaryRenderer> renderers) {
		EnumMap<SalesOrderSummaryExportFormat, SalesOrderSummaryRenderer> values = new EnumMap<>(SalesOrderSummaryExportFormat.class);
		for (SalesOrderSummaryRenderer renderer : Objects.requireNonNull(renderers, "Renderers are required")) {
			if (values.put(renderer.format(), renderer) != null) throw new IllegalArgumentException("Duplicate sales order summary renderer");
		}
		this.renderers = Map.copyOf(values);
	}

	public byte[] render(SalesOrderSummarySnapshot snapshot, SalesOrderSummaryExportFormat format) {
		SalesOrderSummaryRenderer renderer = renderers.get(Objects.requireNonNull(format, "Export format is required"));
		if (renderer == null) throw new IllegalArgumentException("Unsupported sales order summary format");
		return renderer.render(Objects.requireNonNull(snapshot, "Snapshot is required"));
	}
}
