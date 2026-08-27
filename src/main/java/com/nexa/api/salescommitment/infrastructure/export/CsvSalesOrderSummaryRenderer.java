package com.nexa.api.salescommitment.infrastructure.export;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryLineSnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class CsvSalesOrderSummaryRenderer implements SalesOrderSummaryRenderer {
	private static final List<String> HEADER = List.of("order_id", "order_number", "status", "priority",
			"requested_delivery_date", "currency", "total_amount", "created_at", "delivery_snapshot", "payment_option",
			"notes", "line_number", "catalog_item_id", "item_name", "presentation", "quantity", "unit",
			"unit_price_amount", "unit_price_currency", "line_subtotal");

	@Override
	public SalesOrderSummaryExportFormat format() { return SalesOrderSummaryExportFormat.CSV; }

	@Override
	public byte[] render(SalesOrderSummarySnapshot snapshot) {
		StringBuilder csv = new StringBuilder();
		row(csv, HEADER);
		if (snapshot.lines().isEmpty()) row(csv, orderCells(snapshot, null, 0));
		else {
			int lineNumber = 1;
			for (SalesOrderSummaryLineSnapshot line : snapshot.lines()) row(csv, orderCells(snapshot, line, lineNumber++));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static List<Object> orderCells(SalesOrderSummarySnapshot order, SalesOrderSummaryLineSnapshot line, int lineNumber) {
		List<Object> cells = new ArrayList<>();
		cells.add(order.id()); cells.add(order.number()); cells.add(order.status()); cells.add(order.priority());
		cells.add(order.requestedDeliveryDate()); cells.add(order.currency()); cells.add(order.total()); cells.add(order.createdAt());
		cells.add(order.deliverySnapshot()); cells.add(order.paymentOption()); cells.add(order.notes()); cells.add(lineNumber);
		cells.add(line == null ? null : line.catalogItemId()); cells.add(line == null ? null : line.itemName());
		cells.add(line == null ? null : line.presentation()); cells.add(line == null ? null : line.quantity());
		cells.add(line == null ? null : line.unit()); cells.add(line == null ? null : line.unitPriceAmount());
		cells.add(line == null ? null : line.unitPriceCurrency()); cells.add(line == null ? null : line.lineSubtotal());
		return cells;
	}

	private static void row(StringBuilder csv, List<?> values) {
		csv.append(values.stream().map(CsvSalesOrderSummaryRenderer::cell).collect(Collectors.joining(","))).append("\r\n");
	}

	static String cell(Object value) {
		if (value == null) return "";
		String text = String.valueOf(value);
		if (text.stripLeading().matches("^[=+\\-@].*")) text = "'" + text;
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}
}
