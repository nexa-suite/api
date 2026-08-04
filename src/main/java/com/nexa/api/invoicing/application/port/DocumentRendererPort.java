package com.nexa.api.invoicing.application.port;

import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;

import java.util.Map;

public interface DocumentRendererPort {
    RenderedDocument render(BusinessDocumentType type, BusinessDocumentFormat format, Map<String, Object> data);
    record RenderedDocument(byte[] content, String contentType, String extension) { }
}
