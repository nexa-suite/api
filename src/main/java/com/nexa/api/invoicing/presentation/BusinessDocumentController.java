package com.nexa.api.invoicing.presentation;

import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.invoicing.application.service.BusinessDocumentServiceFacade;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.nio.charset.StandardCharsets;
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
    @PostMapping("/business-document-generation-requests")
    @Operation(operationId = "requestBusinessDocumentGeneration")
    public ResponseEntity<BusinessDocumentModels.GenerationRequestView> request(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestHeader("Idempotency-Key") String key, @RequestBody GenerationRequest request) { var value = service.request(context, request.subjectType(), request.subjectId(), request.documentType(), request.format(), key); return ResponseEntity.accepted().body(value); }
    @PostMapping("/business-documents/{documentId}/regenerations")
    @Operation(operationId = "regenerateBusinessDocument")
    public ResponseEntity<BusinessDocumentModels.GenerationRequestView> regenerate(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId, @RequestHeader("Idempotency-Key") String key) { return ResponseEntity.accepted().body(service.regenerate(context, documentId, key)); }
    @GetMapping("/business-documents/{documentId}/downloads")
    @Operation(operationId = "downloadBusinessDocument")
    public ResponseEntity<byte[]> download(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID documentId) { var value = service.download(context, documentId); HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(value.contentType())); headers.setContentLength(value.content().length); headers.setContentDisposition(ContentDisposition.attachment().filename(value.filename(), StandardCharsets.UTF_8).build()); headers.set("X-Content-SHA256", value.checksumSha256()); return new ResponseEntity<>(value.content(), headers, org.springframework.http.HttpStatus.OK); }
    @PostMapping(value = "/business-document-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadBusinessDocumentEvidence")
    public ResponseEntity<BusinessDocumentModels.EvidenceView> uploadEvidence(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestParam String subjectType, @RequestParam UUID subjectId, @RequestPart("file") MultipartFile file) { try { var value = service.uploadEvidence(context, subjectType, subjectId, file.getOriginalFilename(), file.getContentType(), file.getBytes()); return ResponseEntity.status(201).body(value); } catch (java.io.IOException exception) { throw new IllegalArgumentException("Evidence could not be read", exception); } }

    public record GenerationRequest(@NotBlank @Size(max = 64) String subjectType, @NotNull UUID subjectId, @NotBlank @Size(max = 64) String documentType, @NotBlank @Size(max = 8) String format) { }
}
