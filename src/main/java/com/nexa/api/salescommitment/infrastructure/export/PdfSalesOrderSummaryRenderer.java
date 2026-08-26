package com.nexa.api.salescommitment.infrastructure.export;

import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryLineSnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;

public final class PdfSalesOrderSummaryRenderer implements SalesOrderSummaryRenderer {
	@Override
	public SalesOrderSummaryExportFormat format() { return SalesOrderSummaryExportFormat.PDF; }

	@Override
	public byte[] render(SalesOrderSummarySnapshot snapshot) {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PageWriter writer = new PageWriter(document);
			writer.line("NEXA SALES ORDER SUMMARY", true);
			writer.line("Operational export - not a tax or legal document.", false);
			writer.line("", false);
			writer.line("Order: " + snapshot.number(), true);
			writer.line("Order ID: " + snapshot.id(), false);
			writer.line("Status: " + snapshot.status(), false);
			writer.line("Priority: " + snapshot.priority(), false);
			writer.line("Requested delivery: " + snapshot.requestedDeliveryDate(), false);
			writer.line("Currency: " + snapshot.currency(), false);
			writer.line("Total: " + snapshot.total(), false);
			writer.line("Created at: " + snapshot.createdAt(), false);
			writer.line("Payment option: " + snapshot.paymentOption(), false);
			writer.line("Delivery snapshot: " + snapshot.deliverySnapshot(), false);
			writer.line("Notes: " + snapshot.notes(), false);
			writer.line("", false);
			writer.line("Lines", true);
			if (snapshot.lines().isEmpty()) writer.line("No lines", false);
			int index = 1;
			for (SalesOrderSummaryLineSnapshot line : snapshot.lines()) {
				writer.line(index++ + ". " + line.itemName() + " | " + line.presentation() + " | " + line.quantity() + " " + line.unit()
						+ " | " + line.unitPriceAmount() + " " + line.unitPriceCurrency() + " | subtotal " + line.lineSubtotal(), false);
			}
			writer.close();
			document.save(output);
			return output.toByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to render sales order summary PDF", exception);
		}
	}

	private static String safeText(String value) {
		if (value == null) return "-";
		String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return ascii.replaceAll("[^\\x20-\\x7E]", "?");
	}

	private static final class PageWriter {
		private final PDDocument document;
		private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
		private PDPageContentStream stream;
		private float y;

		private PageWriter(PDDocument document) throws IOException {
			this.document = document;
			openPage();
		}

		private void line(String value, boolean heading) throws IOException {
			if (y < 52) {
				endPage();
				openPage();
			}
			stream.setFont(heading ? bold : regular, heading ? 12 : 9);
			stream.showText(safeText(value == null ? "-" : value));
			stream.newLine();
			y -= heading ? 17 : 13;
		}

		private void openPage() throws IOException {
			document.addPage(new PDPage());
			stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
			stream.beginText();
			stream.newLineAtOffset(48, 750);
			y = 750;
		}

		private void endPage() throws IOException {
			stream.endText();
			stream.close();
		}

		private void close() throws IOException { endPage(); }
	}
}
