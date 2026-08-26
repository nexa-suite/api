package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** Selects a dedicated renderer without exposing a generic document model. */
@Component
@Profile("!test")
public final class DedicatedDocumentRenderer implements DocumentRendererPort {
    private final List<BusinessDocumentRenderer> renderers;

    public DedicatedDocumentRenderer(List<BusinessDocumentRenderer> renderers) {
        this.renderers = List.copyOf(renderers);
    }

    @Override
    public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        return renderers.stream().filter(renderer -> renderer.supports(projection, format)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No dedicated renderer exists for " + projection.type()))
                .render(projection, format);
    }

    @Override public boolean supports(BusinessDocumentType type, BusinessDocumentFormat format) {
        return renderers.stream().anyMatch(renderer -> renderer.supports(new TypeOnlyProjection(type), format));
    }

    private record TypeOnlyProjection(BusinessDocumentType type) implements DocumentProjection {
        @Override public String subjectId() { return "type-probe"; }
        @Override public String reference() { return "type-probe"; }
        @Override public java.time.Instant issueDate() { return java.time.Instant.EPOCH; }
        @Override public String status() { return "TYPE_PROBE"; }
        @Override public com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.BusinessParty issuer() { return null; }
        @Override public com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.BusinessParty buyer() { return null; }
        @Override public java.util.List<com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentLine> lines() { return java.util.List.of(); }
        @Override public com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentTotals totals() { return null; }
        @Override public com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DeliveryInfo delivery() { return null; }
        @Override public String paymentTerms() { return null; }
        @Override public String notes() { return null; }
    }
}
