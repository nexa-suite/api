package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.BusinessParty;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.CommercialInvoiceDraftProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DeliveryGuideDraftProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentTotals;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.OrderSummaryProjection;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDocumentRendererTests {
    @Test
    void csvContainsActualLinesAndTotalsWithoutGenericMapRows() {
        byte[] content = new OrderSummaryCsvRenderer().render(order(), BusinessDocumentFormat.CSV).content();
        String csv = new String(content, StandardCharsets.UTF_8);

        assertThat(csv).contains("order_number", "SO-2026-000001", "SKU-COLD-001", "Frozen seafood", "125.50");
        assertThat(csv).doesNotContain("field,value");
    }

    @Test
    void pdfContainsBusinessPartiesLinesAndTotals() throws Exception {
        byte[] content = new OrderSummaryPdfRenderer().render(order(), BusinessDocumentFormat.PDF).content();

        assertThat(content).startsWith(new byte[] {'%', 'P', 'D', 'F'});
        try (var document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("SO-2026-000001", "Nexa Foods", "Buyer Cold Chain", "SKU-COLD-001", "125.50");
        }
    }

    @Test
    void invoiceXmlUsesStructuredUblNamespacesAndRealLineValues() {
        CommercialInvoiceDraftProjection invoice = new CommercialInvoiceDraftProjection(
                order().subjectId(), order().issuer(), order().buyer(), "F-DRAFT-SO-2026-000001", order().reference(), order().issueDate(),
                order().status(), order().lines(), order().totals(), order().delivery(), order().paymentTerms(), order().notes());

        String xml = new String(new InvoiceDraftXmlRenderer().render(invoice, BusinessDocumentFormat.XML).content(), StandardCharsets.UTF_8);

        assertThat(xml).contains("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2", "<cac:InvoiceLine>", "SKU-COLD-001", "125.50", "<cac:TaxTotal>");
        assertThat(xml).doesNotContain("processContents=\"skip\"", "<field>");
    }

    @Test
    void despatchXmlRequiresStructuredDeliveryLines() {
        DeliveryGuideDraftProjection guide = new DeliveryGuideDraftProjection(
                order().subjectId(), order().issuer(), order().buyer(), "DSP-0001", order().reference(), order().issueDate(),
                "SCHEDULED", order().lines(), order().totals(), order().delivery(), order().paymentTerms(), order().notes());

        String xml = new String(new DeliveryGuideXmlRenderer().render(guide, BusinessDocumentFormat.XML).content(), StandardCharsets.UTF_8);

        assertThat(xml).contains("urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2", "<cac:Shipment>", "<cac:DespatchLine>", "Frozen seafood");
    }

    @Test
    void structuredInvoiceRejectsAnIncompleteProjection() {
        var value = order();
        var incomplete = new CommercialInvoiceDraftProjection(value.subjectId(), value.issuer(), value.buyer(), "F-DRAFT-EMPTY",
                value.reference(), value.issueDate(), value.status(), List.of(), value.totals(), value.delivery(), value.paymentTerms(), value.notes());

        assertThatThrownBy(() -> new InvoiceDraftXmlRenderer().render(incomplete, BusinessDocumentFormat.XML))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XML schema validation failed");
    }

    @Test
    void dedicatedRegistryDoesNotAdvertiseUnsupportedFormats() {
        var registry = new DedicatedDocumentRenderer(List.of(new OrderSummaryPdfRenderer(), new OrderSummaryCsvRenderer(), new InvoiceDraftXmlRenderer(), new DeliveryGuideXmlRenderer()));

        assertThat(registry.supports(BusinessDocumentType.ORDER_SUMMARY, BusinessDocumentFormat.PDF)).isTrue();
        assertThat(registry.supports(BusinessDocumentType.ORDER_SUMMARY, BusinessDocumentFormat.XML)).isFalse();
        assertThat(registry.supports(BusinessDocumentType.COMMERCIAL_INVOICE_DRAFT, BusinessDocumentFormat.XML)).isTrue();
    }

    private static OrderSummaryProjection order() {
        BusinessParty issuer = new BusinessParty("Nexa Foods", "RUC-20123456789", "RUC", "20123456789", "Av. Frío 100");
        BusinessParty buyer = new BusinessParty("Buyer Cold Chain", "BUY-001", "RUC", "20987654321", "Jr. Congelado 20");
        DocumentLine line = new DocumentLine("SKU-COLD-001", "Frozen seafood", "Box 10 kg", new BigDecimal("2"), "BOX",
                new BigDecimal("70.00"), new BigDecimal("7.25"), new BigDecimal("62.75"), new BigDecimal("125.50"), "PEN", new BigDecimal("20"));
        return new OrderSummaryProjection("1db4a7b8-a8ce-4573-9f5b-64ac5f08e001", issuer, buyer, "SO-2026-000001",
                Instant.parse("2026-08-04T10:15:00Z"), "CONFIRMED", List.of(line),
                new DocumentTotals(new BigDecimal("125.50"), BigDecimal.ZERO, new BigDecimal("125.50"), "PEN"),
                new BusinessDocumentProjections.DeliveryInfo("Av. Frío 100", "WH-01", "LIMA-NORTE", null, null, null, null),
                "BANK_TRANSFER", "Cold-chain order");
    }
}
