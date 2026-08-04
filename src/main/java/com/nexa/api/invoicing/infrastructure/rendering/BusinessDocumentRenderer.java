package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;

/** One immutable projection/format renderer. */
interface BusinessDocumentRenderer {
    boolean supports(DocumentProjection projection, BusinessDocumentFormat format);
    RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format);
}
