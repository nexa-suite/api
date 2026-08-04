package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.BusinessParty;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentTotals;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.util.List;

final class PdfDocumentSupport {
    private PdfDocumentSupport() { }

    static byte[] render(String title, DocumentProjection projection, List<String> extraLines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PageWriter writer = new PageWriter(document);
            writer.line("NEXA - " + title, true);
            writer.line("Service document; not a SUNAT-certified fiscal document.", false);
            writer.line("Reference: " + projection.reference(), true);
            writer.line("Status: " + projection.status(), false);
            writer.line("Issue date: " + projection.issueDate().atOffset(ZoneOffset.UTC), false);
            party(writer, "Issuer", projection.issuer());
            party(writer, "Buyer", projection.buyer());
            writer.line("Delivery", true);
            writer.line("Address: " + value(projection.delivery().address()), false);
            writer.line("Warehouse: " + value(projection.delivery().warehouse()), false);
            writer.line("Route: " + value(projection.delivery().route()), false);
            writer.line("Dispatch: " + value(projection.delivery().dispatch()), false);
            writer.line("Carrier: " + value(projection.delivery().carrier()), false);
            writer.line("Vehicle: " + value(projection.delivery().vehicle()), false);
            writer.line("Lines", true);
            if (projection.lines().isEmpty()) writer.line("No business lines", false);
            int index = 1;
            for (DocumentLine line : projection.lines()) {
                writer.line(index++ + ". " + line.skuCode() + " | " + line.productFamily() + " | " + line.presentation(), false);
                writer.line("   " + line.quantity() + " " + line.uom() + " x " + line.effectiveUnitPrice() + " " + line.currency()
                        + " | discount " + line.discount() + " | line total " + line.lineTotal(), false);
            }
            DocumentTotals totals = projection.totals();
            writer.line("Totals", true);
            writer.line("Subtotal: " + totals.subtotal() + " " + totals.currency(), false);
            writer.line("Tax: " + totals.tax() + " " + totals.currency(), false);
            writer.line("Total: " + totals.total() + " " + totals.currency(), true);
            if (projection.paymentTerms() != null) writer.line("Payment terms: " + projection.paymentTerms(), false);
            if (projection.notes() != null) writer.line("Notes: " + projection.notes(), false);
            for (String extraLine : extraLines == null ? List.<String>of() : extraLines) writer.line(extraLine, false);
            writer.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Business document PDF rendering failed", exception);
        }
    }

    private static void party(PageWriter writer, String label, BusinessParty party) throws IOException {
        writer.line(label, true);
        writer.line("Name: " + party.legalName(), false);
        if (party.businessIdentifier() != null) writer.line("Business identifier: " + party.businessIdentifier(), false);
        if (party.taxIdentifierValue() != null) writer.line("Tax identity: " + value(party.taxIdentifierType()) + " " + party.taxIdentifierValue(), false);
        if (party.address() != null) writer.line("Address: " + party.address(), false);
    }

    private static String value(String value) { return value == null || value.isBlank() ? "-" : value; }

    private static String safeText(String value) {
        String ascii = Normalizer.normalize(value == null ? "-" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String safe = ascii.replaceAll("[^\\x20-\\x7E]", "?").replaceAll("[\\r\\n\\t]", " ");
        return safe.length() > 220 ? safe.substring(0, 217) + "..." : safe;
    }

    private static final class PageWriter {
        private final PDDocument document;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream stream;
        private float y;

        private PageWriter(PDDocument document) throws IOException { this.document = document; openPage(); }

        private void line(String value, boolean heading) throws IOException {
            if (y < 52) { endPage(); openPage(); }
            stream.setFont(heading ? bold : regular, heading ? 11 : 8.5f);
            stream.showText(safeText(value));
            stream.newLine();
            y -= heading ? 16 : 12;
        }

        private void openPage() throws IOException {
            document.addPage(new PDPage());
            stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
            stream.beginText();
            stream.setLeading(12);
            stream.newLineAtOffset(44, 752);
            y = 752;
        }

        private void endPage() throws IOException { stream.endText(); stream.close(); }
        private void close() throws IOException { endPage(); }
    }
}
