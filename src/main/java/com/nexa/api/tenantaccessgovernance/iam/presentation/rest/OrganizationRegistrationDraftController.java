package com.nexa.api.tenantaccessgovernance.iam.presentation.rest;

import com.nexa.api.tenantaccessgovernance.iam.application.onboarding.OrganizationRegistrationDraftModels;
import com.nexa.api.tenantaccessgovernance.iam.application.onboarding.OrganizationRegistrationDraftPort;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.OrganizationRegistrationDraftException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** Anonymous token-scoped HTTP boundary for the six-step onboarding draft. */
@RestController
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/tenant-management/organization-registration-drafts")
@Tag(name = "Organization onboarding")
public final class OrganizationRegistrationDraftController {
    private final OrganizationRegistrationDraftPort drafts;

    public OrganizationRegistrationDraftController(OrganizationRegistrationDraftPort drafts) {
        this.drafts = drafts;
    }

    @PostMapping
    @Operation(operationId = "createOrganizationRegistrationDraft")
    public ResponseEntity<DraftResponse> create() {
        var value = drafts.create();
        return ResponseEntity.created(URI.create("/api/v1/tenant-management/organization-registration-drafts/" + value.draft().registrationId()))
                .eTag(etag(value.draft().version())).body(response(value.draft(), value.resumeToken()));
    }

    @GetMapping("/{registrationId}")
    @Operation(operationId = "getOrganizationRegistrationDraft")
    public ResponseEntity<DraftResponse> get(@PathVariable UUID registrationId, HttpHeaders headers) {
        var value = drafts.get(registrationId, token(headers));
        return ResponseEntity.ok().eTag(etag(value.version())).body(response(value, null));
    }

    @PutMapping("/{registrationId}/steps/{step}")
    @Operation(operationId = "updateOrganizationRegistrationDraftStep")
    public ResponseEntity<DraftResponse> updateStep(@PathVariable UUID registrationId, @PathVariable int step,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpHeaders headers, @RequestBody(required = false) Map<String, Object> values) {
        var value = drafts.updateStep(registrationId, token(headers), version(ifMatch), step,
                values == null ? Map.of() : values, idempotencyKey);
        return ResponseEntity.ok().eTag(etag(value.version())).body(response(value, null));
    }

    @PostMapping("/{registrationId}/submit")
    @Operation(operationId = "submitOrganizationRegistrationDraft")
    public ResponseEntity<DraftResponse> submit(@PathVariable UUID registrationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpHeaders headers) {
        var value = drafts.submit(registrationId, token(headers), version(ifMatch), idempotencyKey);
        return ResponseEntity.ok().eTag(etag(value.version())).body(response(value, null));
    }

    private static String token(HttpHeaders headers) {
        String value = headers.getFirst("X-Resume-Token");
        if (value == null || value.isBlank()) value = headers.getFirst("X-Organization-Registration-Token");
        return value;
    }

    private static int version(String value) {
        if (value == null || value.isBlank()) throw new OrganizationRegistrationDraftException("PRECONDITION_REQUIRED");
        try { return Integer.parseInt(value.replace("W/", "").replace("\"", "").trim()); }
        catch (NumberFormatException ignored) { throw new OrganizationRegistrationDraftException("PRECONDITION_REQUIRED"); }
    }

    private static String etag(long version) { return "\"" + version + "\""; }
    private static DraftResponse response(OrganizationRegistrationDraftModels.Draft value, String resumeToken) {
        return new DraftResponse(value.registrationId(), value.status(), value.lastCompletedStep(), value.completedSteps(),
                value.data(), value.version(), value.createdAt(), value.updatedAt(), resumeToken);
    }

    public record DraftResponse(UUID registrationId, String status, int lastCompletedStep, java.util.Set<Integer> completedSteps,
            Map<String, Object> data, long version, java.time.Instant createdAt, java.time.Instant updatedAt, String resumeToken) { }
}
