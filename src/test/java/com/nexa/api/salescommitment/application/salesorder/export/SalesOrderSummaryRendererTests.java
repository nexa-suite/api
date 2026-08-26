package com.nexa.api.salescommitment.application.salesorder.export;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryLineSnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.application.salesorder.export.service.SalesOrderSummaryRendererStrategy;
import com.nexa.api.salescommitment.infrastructure.export.CsvSalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.infrastructure.export.PdfSalesOrderSummaryRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderSummaryRendererTests {
	@Test
	void csvIsUtf8QuotedAndNeutralizesFormulaCells() {
		String content = new String(new CsvSalesOrderSummaryRenderer().render(snapshot("=SUM(A1)")), StandardCharsets.UTF_8);

		assertThat(content).contains("\"'=SUM(A1)\"");
		assertThat(content).contains("\"note, with \"\"quotes\"\"\"");
		assertThat(content).contains("\"Av. Lima, Café frío\"");
	}

	@Test
	void pdfIsReadableAndStatesOperationalNonLegalPurpose() throws Exception {
		byte[] content = new PdfSalesOrderSummaryRenderer().render(snapshot("Item"));

		assertThat(content).startsWith(new byte[] {'%', 'P', 'D', 'F'});
		try (var document = Loader.loadPDF(content)) {
			assertThat(document.getNumberOfPages()).isGreaterThan(0);
			assertThat(new PDFTextStripper().getText(document)).contains("not a tax or legal document");
		}
	}

	@Test
	void strategySelectsTheRequestedRenderer() {
		SalesOrderSummaryRenderer csv = new CsvSalesOrderSummaryRenderer();
		SalesOrderSummaryRenderer pdf = new PdfSalesOrderSummaryRenderer();
		SalesOrderSummaryRendererStrategy strategy = new SalesOrderSummaryRendererStrategy(List.of(csv, pdf));

		assertThat(strategy.render(snapshot("Item"), SalesOrderSummaryExportFormat.CSV)).containsExactly(csv.render(snapshot("Item")));
		assertThat(strategy.render(snapshot("Item"), SalesOrderSummaryExportFormat.PDF)).startsWith(new byte[] {'%', 'P', 'D', 'F'});
	}

	private static SalesOrderSummarySnapshot snapshot(String itemName) {
		return new SalesOrderSummarySnapshot("a7a5a7d1-49a0-4d5c-a9d7-0b65784c6991", "SO-2026-000001",
				"3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10", "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17",
				"c7e9ab18-114e-4b91-9bf7-72172aa9a0a4", "NORMAL", LocalDate.of(2026, 8, 4),
				"Av. Lima, Café frío", "BANK_TRANSFER", "note, with \"quotes\"", "PEN", new BigDecimal("125.50"),
				"CONFIRMED", Instant.parse("2026-08-02T10:15:00Z"), List.of(new SalesOrderSummaryLineSnapshot(
					"f1d7e4fd-d3f9-42b3-94e3-58c2e5f5bfb8", itemName, "Caja 10 unidades", new BigDecimal("2"),
					"BOX", new BigDecimal("62.75"), "PEN", new BigDecimal("125.50"))));
	}
}
