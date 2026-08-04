package com.nexa.api.invoicing.infrastructure.rendering;

import com.nexa.api.invoicing.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;

final class RendererSupport {
    private RendererSupport() { }

    static boolean supports(DocumentProjection projection, BusinessDocumentType type, BusinessDocumentFormat format) {
        return projection.type() == type;
    }

    static <T extends DocumentProjection> T require(DocumentProjection projection, Class<T> expected, BusinessDocumentType type,
            BusinessDocumentFormat format) {
        if (!supports(projection, type, format) || !expected.isInstance(projection)) {
            throw new IllegalArgumentException("Projection does not match " + type + " " + format);
        }
        return expected.cast(projection);
    }

}
