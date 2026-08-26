package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.PurchaseRequestSummaryProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class PurchaseRequestSummaryPdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.PURCHASE_REQUEST_SUMMARY && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        PurchaseRequestSummaryProjection value = RendererSupport.require(projection, PurchaseRequestSummaryProjection.class, BusinessDocumentType.PURCHASE_REQUEST_SUMMARY, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("Purchase Request Summary", value, List.of(
                "Requested delivery date: " + (value.requestedDeliveryDate() == null ? "-" : value.requestedDeliveryDate()),
                "Review note: " + (value.reviewNote() == null ? "-" : value.reviewNote()))), "application/pdf", "pdf");
    }
}
