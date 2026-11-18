package com.nexa.api.tenantaccessgovernance.iam.presentation.rest;

import com.nexa.api.tenantaccessgovernance.iam.application.service.LegalAcceptance;
import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.LegalDocumentChangedException;
import org.springframework.http.HttpStatus;

@RestController
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/me/legal-acceptance")
public class LegalAcceptanceController {
    private final LegalAcceptance acceptance;
    public LegalAcceptanceController(LegalAcceptance acceptance) { this.acceptance = acceptance; }

    @GetMapping
    public LegalAcceptanceModels.View current(Authentication authentication) {
        return acceptance.current(userId(authentication));
    }

    @PostMapping
    public LegalAcceptanceModels.View accept(Authentication authentication, @RequestBody LegalAcceptanceModels.Request request) {
        return acceptance.accept(userId(authentication), request);
    }

    @ExceptionHandler(LegalDocumentChangedException.class)
    ResponseEntity<ProblemDetail> conflict(LegalDocumentChangedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage()));
    }

    private static UUID userId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) throw new AccessDeniedException("Authenticated JWT is required");
        try { return UUID.fromString(jwt.getToken().getSubject()); }
        catch (RuntimeException exception) { throw new AccessDeniedException("Invalid authenticated subject", exception); }
    }
}
