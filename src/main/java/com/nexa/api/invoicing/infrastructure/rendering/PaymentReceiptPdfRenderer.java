package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.PaymentReceiptProjection;
import com.nexa.api.invoicing.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class PaymentReceiptPdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.PAYMENT_RECEIPT && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        PaymentReceiptProjection value = RendererSupport.require(projection, PaymentReceiptProjection.class, BusinessDocumentType.PAYMENT_RECEIPT, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("Payment Receipt", value, List.of("Payment method: " + value.method(), "Paid amount: " + value.paidAmount() + " " + value.totals().currency(), "Allocation: " + value.allocation(), "Provider reference: " + (value.providerReference() == null ? "-" : value.providerReference()))), "application/pdf", "pdf");
    }
}
