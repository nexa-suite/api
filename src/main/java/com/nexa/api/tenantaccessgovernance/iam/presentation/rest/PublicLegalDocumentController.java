package com.nexa.api.tenantaccessgovernance.iam.presentation.rest;

import com.nexa.api.tenantaccessgovernance.iam.application.service.LegalAcceptance;
import io.swagger.v3.oas.annotations.Operation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicLegalDocumentController {
    @Operation(operationId = "getCurrentMobileTerms")
    @GetMapping("/api/v1/legal/mobile-terms")
    public Document current() {
        return new Document(LegalAcceptance.DOCUMENT_ID, LegalAcceptance.VERSION, LegalAcceptance.CONTENT, LegalAcceptance.DIGEST);
    }

    public record Document(String documentId, String version, String content, String contentDigest) { }
}
