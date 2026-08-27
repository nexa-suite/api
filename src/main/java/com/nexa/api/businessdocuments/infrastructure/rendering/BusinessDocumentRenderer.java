package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;

/** One immutable projection/format renderer. */
interface BusinessDocumentRenderer {
    boolean supports(DocumentProjection projection, BusinessDocumentFormat format);
    RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format);
}
