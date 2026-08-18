package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.CommercialInvoiceDraftProjection;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class InvoiceDraftXmlRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.COMMERCIAL_INVOICE_DRAFT && format == BusinessDocumentFormat.XML; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        CommercialInvoiceDraftProjection value = RendererSupport.require(projection, CommercialInvoiceDraftProjection.class, BusinessDocumentType.COMMERCIAL_INVOICE_DRAFT, BusinessDocumentFormat.XML);
        return new RenderedDocument(XmlDocumentSupport.invoice(value), "application/xml", "xml");
    }
}
