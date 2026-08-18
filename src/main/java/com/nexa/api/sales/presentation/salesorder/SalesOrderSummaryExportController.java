package com.nexa.api.sales.presentation.salesorder;

import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Tag(name = "Sales Order Exports")
@SecurityRequirement(name = "bearerAuth")
public final class SalesOrderSummaryExportController {
	private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final SalesOrderSummaryExportUseCase exports;

	public SalesOrderSummaryExportController(SalesOrderSummaryExportUseCase exports) { this.exports = exports; }

	@GetMapping("/api/v1/sales-orders/{id}/summary")
	@Operation(operationId = "exportSalesOrderSummary")
	public ResponseEntity<byte[]> export(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id, @RequestParam(defaultValue = "PDF") String format) {
		return response(exports.export(context, id, SalesOrderSummaryExportFormat.from(format)));
	}

	@GetMapping("/api/v1/my-orders/{id}/summary")
	@Operation(operationId = "exportMySalesOrderSummary")
	public ResponseEntity<byte[]> exportMyOrderSummary(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id, @RequestParam(defaultValue = "PDF") String format) {
		return export(context, id, format);
	}

	@GetMapping("/api/v1/sales-orders/{id}/exports/summary.pdf")
	@Operation(operationId = "exportSalesOrderSummaryPdf")
	public ResponseEntity<byte[]> exportPdf(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id) {
		return response(exports.export(context, id, SalesOrderSummaryExportFormat.PDF));
	}

	@GetMapping("/api/v1/buyer/orders/{id}/exports/summary.pdf")
	@Operation(operationId = "exportBuyerSalesOrderSummaryPdf")
	public ResponseEntity<byte[]> exportBuyerPdf(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id) {
		return exportPdf(context, id);
	}

	@GetMapping("/api/v1/sales-orders/{id}/exports/summary.csv")
	@Operation(operationId = "exportSalesOrderSummaryCsv")
	public ResponseEntity<byte[]> exportCsv(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id) {
		return response(exports.export(context, id, SalesOrderSummaryExportFormat.CSV));
	}

	@GetMapping("/api/v1/buyer/orders/{id}/exports/summary.csv")
	@Operation(operationId = "exportBuyerSalesOrderSummaryCsv")
	public ResponseEntity<byte[]> exportBuyerCsv(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable String id) {
		return exportCsv(context, id);
	}

	private ResponseEntity<byte[]> response(SalesOrderSummaryExportResult result) {
		byte[] content = result.content();
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(result.contentType()))
				.contentLength(content.length)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
				.body(content);
	}
}
