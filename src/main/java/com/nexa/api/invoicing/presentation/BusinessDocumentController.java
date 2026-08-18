package com.nexa.api.invoicing.presentation;

import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.invoicing.application.service.BusinessDocumentServiceFacade;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Business Documents")
@SecurityRequirement(name = "bearerAuth")
public final class BusinessDocumentController {
    private static final String ACCESS = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final BusinessDocumentServiceFacade service;
    public BusinessDocumentController(BusinessDocumentServiceFacade service) { this.service = service; }

    @GetMapping("/business-documents")
    @Operation(operationId = "listBusinessDocuments")
    public BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String documentType, @RequestParam(required = false) String status) { return service.list(context, page, size, documentType, status); }
    @GetMapping("/business-documents/{documentId}")
    @Operation(operationId = "getBusinessDocument")
    public BusinessDocumentModels.DocumentView get(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId) { return service.get(context, documentId); }
    @GetMapping("/business-documents/{documentId}/events")
    @Operation(operationId = "listBusinessDocumentEvents")
    public List<BusinessDocumentModels.DocumentEventView> events(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId) { return service.events(context, documentId); }
    @PostMapping("/business-document-generation-requests")
    @Operation(operationId = "requestBusinessDocumentGeneration")
    public ResponseEntity<BusinessDocumentModels.GenerationRequestView> request(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody GenerationRequest request) { var value = service.request(context, request.subjectType(), request.subjectId(), request.documentType(), request.format(), key); return ResponseEntity.accepted().location(URI.create("/api/v1/business-documents/" + value.documentId())).body(value); }
    @PostMapping("/business-documents/{documentId}/regenerations")
    @Operation(operationId = "regenerateBusinessDocument")
    public ResponseEntity<BusinessDocumentModels.GenerationRequestView> regenerate(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId, @RequestHeader("Idempotency-Key") String key) { var value = service.regenerate(context, documentId, key); return ResponseEntity.accepted().location(URI.create("/api/v1/business-documents/" + value.documentId())).body(value); }
    @GetMapping("/business-documents/{documentId}/downloads")
    @Operation(operationId = "downloadBusinessDocument")
    public ResponseEntity<StreamingResponseBody> download(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId) { return stream(service.download(context, documentId)); }

    @PostMapping("/business-document-evidence/requests")
    @Operation(operationId = "requestBusinessDocumentEvidence")
    public ResponseEntity<BusinessDocumentModels.EvidenceView> requestEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody EvidenceRequest request) {
        var value = service.requestEvidence(context, request.subjectType(), request.subjectId(), request.originalFilename(), request.declaredContentType(), key);
        return ResponseEntity.created(URI.create("/api/v1/business-document-evidence/" + value.id())).body(value);
    }

    @PutMapping(value = "/business-document-evidence/{evidenceId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "completeBusinessDocumentEvidence")
    public BusinessDocumentModels.EvidenceView completeEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID evidenceId, @RequestPart("file") MultipartFile file) {
        try (var input = file.getInputStream()) {
            return service.completeEvidence(context, evidenceId, file.getOriginalFilename(), file.getContentType(), input, file.getSize(), key);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Evidence could not be read", exception);
        }
    }
    @PostMapping(value = "/business-document-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadBusinessDocumentEvidence")
    public ResponseEntity<BusinessDocumentModels.EvidenceView> uploadEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestHeader("Idempotency-Key") String key, @RequestParam String subjectType, @RequestParam UUID subjectId, @RequestPart("file") MultipartFile file) {
        var requested = service.requestEvidence(context, subjectType, subjectId, file.getOriginalFilename(), file.getContentType(), key);
        try (var input = file.getInputStream()) {
            var value = service.completeEvidence(context, UUID.fromString(requested.id()), file.getOriginalFilename(), file.getContentType(), input, file.getSize(), key);
            return ResponseEntity.created(URI.create("/api/v1/business-document-evidence/" + value.id())).body(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Evidence could not be read", exception);
        }
    }

    @GetMapping("/business-document-evidence/{evidenceId}")
    @Operation(operationId = "getBusinessDocumentEvidence")
    public BusinessDocumentModels.EvidenceView getEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID evidenceId) { return service.evidence(context, evidenceId); }

    @GetMapping("/business-document-evidence")
    @Operation(operationId = "listBusinessDocumentEvidence")
    public BusinessDocumentModels.Page<BusinessDocumentModels.EvidenceView> listEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestParam(required = false) String subjectType, @RequestParam(required = false) UUID subjectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return service.listEvidence(context, subjectType, subjectId, page, size); }

    @GetMapping("/business-document-evidence/{evidenceId}/downloads")
    @Operation(operationId = "downloadBusinessDocumentEvidence")
    public ResponseEntity<StreamingResponseBody> downloadEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID evidenceId) { return stream(service.downloadEvidence(context, evidenceId)); }

    @DeleteMapping("/business-document-evidence/{evidenceId}")
    @Operation(operationId = "deleteBusinessDocumentEvidence")
    public ResponseEntity<Void> deleteEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID evidenceId) { service.deleteEvidence(context, evidenceId); return ResponseEntity.noContent().build(); }

    private static ResponseEntity<StreamingResponseBody> stream(BusinessDocumentModels.Download value) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(parseContentType(value.contentType()));
        headers.setContentLength(value.byteSize());
        headers.setContentDisposition(ContentDisposition.attachment().filename(value.filename(), StandardCharsets.UTF_8).build());
        headers.set("X-Content-SHA256", value.checksumSha256());
        headers.setCacheControl("private, no-store");
        StreamingResponseBody body = output -> { try (var input = value.content()) { input.transferTo(output); } };
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    private static MediaType parseContentType(String value) {
        try { return MediaType.parseMediaType(value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : value); }
        catch (IllegalArgumentException exception) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    public record GenerationRequest(@NotBlank @Size(max = 64) String subjectType, @NotNull UUID subjectId, @NotBlank @Size(max = 64) String documentType, @NotBlank @Size(max = 8) String format) { }
    public record EvidenceRequest(@NotBlank @Size(max = 64) String subjectType, @NotNull UUID subjectId, @NotBlank @Size(max = 255) String originalFilename, @NotBlank @Size(max = 160) String declaredContentType) { }
}
