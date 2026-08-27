package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DeliveryGuideDraftProjection;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class DeliveryGuidePdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.DELIVERY_GUIDE_DRAFT && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        DeliveryGuideDraftProjection value = RendererSupport.require(projection, DeliveryGuideDraftProjection.class, BusinessDocumentType.DELIVERY_GUIDE_DRAFT, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("Delivery Guide Draft", value, List.of("NON-FISCAL-DRAFT: delivery guide information only")), "application/pdf", "pdf");
    }
}
