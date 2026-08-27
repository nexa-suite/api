package com.nexa.api.businessdocuments.infrastructure.rendering;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentProjection;
import com.nexa.api.businessdocuments.application.port.DocumentRendererPort.RenderedDocument;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class PurchaseRequestSummaryCsvRenderer implements BusinessDocumentRenderer {
    @Override public boolean supports(DocumentProjection projection, BusinessDocumentFormat format) { return projection.type() == BusinessDocumentType.PURCHASE_REQUEST_SUMMARY && format == BusinessDocumentFormat.CSV; }
    @Override public RenderedDocument render(DocumentProjection projection, BusinessDocumentFormat format) {
        RendererSupport.require(projection, com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.PurchaseRequestSummaryProjection.class, BusinessDocumentType.PURCHASE_REQUEST_SUMMARY, BusinessDocumentFormat.CSV);
        return new RenderedDocument(CsvDocumentSupport.render(projection), "text/csv; charset=UTF-8", "csv");
    }
}
