package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Assignee;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Snapshot;
import com.nexa.api.fulfillmentdelivery.application.service.BomOperationalExceptionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Business Operations Manager coordination API; no direct execution or stock disposition commands. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/operational-exceptions")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class BomOperationalExceptionController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final BomOperationalExceptionService service;

    public BomOperationalExceptionController(BomOperationalExceptionService service) { this.service = service; }

    @GetMapping
    @Operation(operationId = "listOperationalExceptionsForCoordination")
    public Snapshot list(@RequestAttribute(ACCESS) CurrentAccessContext context) {
        return service.list(context);
    }

    @GetMapping("/{exceptionId}/assignees")
    @Operation(operationId = "listOperationalExceptionAssignees")
    public List<Assignee> assignees(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                    @PathVariable UUID exceptionId) {
        return service.assignees(context, exceptionId);
    }

    @Schema(name = "OperationalExceptionReasonRequest")
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }
    @Schema(name = "OperationalExceptionAssignmentRequest")
    public record AssignmentRequest(@NotBlank @Size(max = 500) String reason, UUID responsibleMembershipId) { }
    public record FollowUpRequest(@NotBlank @Size(max = 500) String reason, @Size(max = 2000) String note) { }

    @PostMapping("/{exceptionId}/claims")
    @Operation(operationId = "claimOperationalExceptionCoordination")
    public ResponseEntity<MutationResult> claim(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID exceptionId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return mutation(service.mutate(context, exceptionId, "CLAIM", null, version(ifMatch), key,
                request.reason(), null));
    }

    @PostMapping("/{exceptionId}/assignments")
    @Operation(operationId = "reassignOperationalExceptionCoordination")
    public ResponseEntity<MutationResult> assign(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID exceptionId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AssignmentRequest request) {
        return mutation(service.mutate(context, exceptionId, "ASSIGN", request.responsibleMembershipId(),
                version(ifMatch), key, request.reason(), null));
    }

    @PostMapping("/{exceptionId}/follow-ups")
    @Operation(operationId = "appendOperationalExceptionFollowUp")
    public ResponseEntity<MutationResult> followUp(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID exceptionId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody FollowUpRequest request) {
        return mutation(service.mutate(context, exceptionId, "FOLLOW_UP", null, version(ifMatch), key,
                request.reason(), request.note()));
    }

    @PostMapping("/{exceptionId}/resolutions")
    @Operation(operationId = "resolveWarningOperationalExceptionCoordination")
    public ResponseEntity<MutationResult> resolve(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID exceptionId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return mutation(service.mutate(context, exceptionId, "RESOLVE", null, version(ifMatch), key,
                request.reason(), null));
    }

    @PostMapping("/{exceptionId}/closures")
    @Operation(operationId = "closeWarningOperationalExceptionCoordination")
    public ResponseEntity<MutationResult> close(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID exceptionId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReasonRequest request) {
        return mutation(service.mutate(context, exceptionId, "CLOSE", null, version(ifMatch), key,
                request.reason(), null));
    }

    private static ResponseEntity<MutationResult> mutation(MutationResult value) {
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.deliveryVersion())).body(value);
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw error("PRECONDITION_REQUIRED");
        String candidate = value.trim();
        if (candidate.startsWith("W/") || candidate.length() < 2 || candidate.charAt(0) != '"'
                || candidate.charAt(candidate.length() - 1) != '"') throw error("VERSION_INVALID");
        try {
            long parsed = Long.parseLong(candidate.substring(1, candidate.length() - 1));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw error("VERSION_INVALID");
        }
    }

    private static com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException error(String code) {
        return new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(code, false);
    }
}
