package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.IncidentReportProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public final class IncidentReportPdfRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.INCIDENT_REPORT && format == BusinessDocumentFormat.PDF; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        IncidentReportProjection value = RendererSupport.require(projection, IncidentReportProjection.class, BusinessDocumentType.INCIDENT_REPORT, BusinessDocumentFormat.PDF);
        return new RenderedDocument(PdfDocumentSupport.render("Incident Report", value, List.of("Incident: " + value.incidentType() + " / " + value.severity(), "Description: " + value.description(), "Temperature: " + value.temperatureSummary(), "Resolution: " + value.resolution(), "Evidence references: " + value.evidenceReferences())), "application/pdf", "pdf");
    }
}
