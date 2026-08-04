package com.nexa.api.invoicing.application.port;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;

public interface DocumentRendererPort {
    RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format);
    default boolean supports(com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType type, BusinessDocumentFormat format) { return true; }
    record RenderedDocument(byte[] content, String contentType, String extension) { }
}
