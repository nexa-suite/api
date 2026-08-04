package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.OrderSummaryProjection;
import com.nexa.api.invoicing.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class OrderSummaryPdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.ORDER_SUMMARY && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        OrderSummaryProjection value = RendererSupport.require(projection, OrderSummaryProjection.class, BusinessDocumentType.ORDER_SUMMARY, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("Order Summary", value, List.of()), "application/pdf", "pdf");
    }
}
