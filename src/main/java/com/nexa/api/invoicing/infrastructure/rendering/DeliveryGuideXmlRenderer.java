package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DeliveryGuideDraftProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class DeliveryGuideXmlRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.DELIVERY_GUIDE_DRAFT && format == BusinessDocumentFormat.XML; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        DeliveryGuideDraftProjection value = RendererSupport.require(projection, DeliveryGuideDraftProjection.class, BusinessDocumentType.DELIVERY_GUIDE_DRAFT, BusinessDocumentFormat.XML);
        return new RenderedDocument(XmlDocumentSupport.deliveryGuide(value), "application/xml", "xml");
    }
}
