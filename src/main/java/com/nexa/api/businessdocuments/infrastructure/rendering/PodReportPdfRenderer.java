package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.PodReportProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class PodReportPdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.POD_REPORT && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        PodReportProjection value = RendererSupport.require(projection, PodReportProjection.class, BusinessDocumentType.POD_REPORT, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("POD Report", value, List.of("Receiver: " + value.receiver(), "Conformity: " + value.conformity(), "Temperature: " + value.temperatureSummary(), "Evidence references: " + value.evidenceReferences())), "application/pdf", "pdf");
    }
}
