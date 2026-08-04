package com.nexa.api.invoicing.infrastructure.persistence;

import com.nexa.api.invoicing.application.port.BusinessDocumentPort;
import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.invoicing.application.port.ContentScannerPort;
import com.nexa.api.invoicing.application.port.DocumentRendererPort;
import com.nexa.api.invoicing.application.port.DocumentSubjectLookupPort;
import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentStatus;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectSnapshot;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectType;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistence adapter for business documents, storage boundary and bounded generation worker. */
@Profile("!test")
@Repository
public class BusinessDocumentService implements BusinessDocumentPort {
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final ContentScannerPort scanner;
    private final DocumentRendererPort renderer;
    private final DocumentSubjectLookupPort subjects;

    public BusinessDocumentService(JdbcTemplate jdbc, ObjectStoragePort storage, ContentScannerPort scanner, DocumentRendererPort renderer, DocumentSubjectLookupPort subjects) {
        this.jdbc = jdbc; this.storage = storage; this.scanner = scanner; this.renderer = renderer; this.subjects = subjects;
    }

    @Transactional
    public BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext context, String subjectType, UUID subjectId, String documentType, String format, String idempotencyKey) {
        requireGeneration(context);
        requireKey(idempotencyKey);
        DocumentSubjectType subject = parseSubject(subjectType); BusinessDocumentType type = parseType(documentType); BusinessDocumentFormat output = parseFormat(format);
        DocumentSubjectSnapshot snapshot = subjects.lookup(tenant(context).toString(), workspace(context).toString(), new DocumentSubjectReference(subject, subjectId.toString()));
        if (!snapshot.subjectExists()) throw new IllegalArgumentException("Document subject not found");
        authorizeClientScope(context, snapshot.clientAccountId());
        String requestHash = sha256(subject.name() + subjectId + type.name() + output.name());
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, n) -> rs.getObject(1),
                tenant(context) + "|" + workspace(context) + "|document-version|" + subject.name() + "|" + subjectId + "|" + type + "|" + output);
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, n) -> rs.getObject(1), tenant(context) + "|" + workspace(context) + "|document-generation|" + context.membershipId().value() + "|" + idempotencyKey);
        var existing = jdbc.query("select r.id,r.document_id,r.subject_type,r.subject_id,r.document_type,r.format,r.status,r.requested_at,r.completed_at,r.request_hash from business_documents.document_generation_request r where r.tenant_id=? and r.workspace_id=? and r.requested_by_membership_id=? and r.idempotency_key=?", (rs, n) -> new RequestClaim(requestView(rs), rs.getString("request_hash")), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey);
        if (!existing.isEmpty()) {
            RequestClaim claim = existing.get(0);
            if (!requestHash.equalsIgnoreCase(claim.requestHash())) throw new IllegalStateException("Idempotency-Key payload conflict");
            return claim.view();
        }
        int version = jdbc.queryForObject("select coalesce(max(version),0)+1 from business_documents.business_document where tenant_id=? and workspace_id=? and subject_type=? and subject_id=? and document_type=? and format=?", Integer.class, tenant(context), workspace(context), subject.name(), subjectId, type.name(), output.name());
        Instant now = Instant.now(); UUID documentId = UUID.randomUUID(); UUID requestId = UUID.randomUUID();
        jdbc.update("insert into business_documents.business_document (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,created_at,updated_at) values (?,?,?,?,?,?,?,?,'REQUESTED',?,?,?)", documentId, tenant(context), workspace(context), uuid(snapshot.clientAccountId()), subject.name(), subjectId, type.name(), version, output.name(), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into business_documents.document_generation_request (id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,requested_at) values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?)", requestId, tenant(context), workspace(context), context.membershipId().value(), documentId, subject.name(), subjectId, type.name(), output.name(), idempotencyKey, requestHash, Timestamp.from(now));
        outbox(context, "BUSINESS_DOCUMENT_GENERATION_REQUESTED", documentId, Map.of("documentId", documentId, "requestId", requestId, "subjectType", subject.name(), "subjectId", subjectId, "documentType", type.name(), "format", output.name()));
        return jdbc.query("select r.id,r.document_id,r.subject_type,r.subject_id,r.document_type,r.format,r.status,r.requested_at,r.completed_at from business_documents.document_generation_request r where r.id=?", (rs, n) -> requestView(rs), requestId).get(0);
    }

    @Transactional(readOnly = true)
    public BusinessDocumentModels.Page<BusinessDocumentModels.DocumentView> list(CurrentAccessContext context, int page, int size, String documentType, String status) {
        read(context); int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size)); String type = blankToNull(documentType), state = blankToNull(status);
        StringBuilder whereBuilder = new StringBuilder("d.tenant_id=? and d.workspace_id=?");
        List<Object> params = new ArrayList<>();
        params.add(tenant(context)); params.add(workspace(context));
        if (type != null) { whereBuilder.append(" and d.document_type=?"); params.add(type); }
        if (state != null) { whereBuilder.append(" and d.status=?"); params.add(state); }
        if (context.hasRole(MembershipRole.BUYER)) {
            whereBuilder.append(" and exists(select 1 from sales.client_account_membership cam where cam.tenant_id=d.tenant_id and cam.workspace_id=d.workspace_id and cam.client_account_id=d.client_account_id and cam.workspace_membership_id=?)");
            params.add(context.membershipId().value());
        }
        String where = whereBuilder.toString();
        long total = jdbc.queryForObject("select count(*) from business_documents.business_document d where " + where, Long.class, params.toArray());
        params.add(safeSize); params.add(safePage * safeSize);
        List<BusinessDocumentModels.DocumentView> rows = jdbc.query("select d.id,d.client_account_id,d.subject_type,d.subject_id,d.document_type,d.document_number,d.version,d.status,d.format,d.storage_object_key,d.checksum_sha256,d.content_type,d.byte_size,d.generated_at,d.failure_code,d.failure_detail,d.created_at,d.updated_at from business_documents.business_document d where " + where + " order by d.created_at desc,d.id limit ? offset ?", (rs, n) -> documentView(rs), params.toArray());
        return new BusinessDocumentModels.Page<>(rows, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public BusinessDocumentModels.DocumentView get(CurrentAccessContext context, UUID documentId) {
        read(context);
        return jdbc.query("select d.id,d.client_account_id,d.subject_type,d.subject_id,d.document_type,d.document_number,d.version,d.status,d.format,d.storage_object_key,d.checksum_sha256,d.content_type,d.byte_size,d.generated_at,d.failure_code,d.failure_detail,d.created_at,d.updated_at from business_documents.business_document d where d.tenant_id=? and d.workspace_id=? and d.id=?", (rs, n) -> documentView(rs), tenant(context), workspace(context), documentId).stream().filter(value -> authorizedDocument(context, value.clientAccountId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Business document not found"));
    }

    @Transactional
    public BusinessDocumentModels.GenerationRequestView regenerate(CurrentAccessContext context, UUID documentId, String idempotencyKey) {
        requireGeneration(context); requireKey(idempotencyKey);
        BusinessDocumentModels.DocumentView current = get(context, documentId);
        return request(context, current.subjectType(), UUID.fromString(current.subjectId()), current.documentType(), current.format(), idempotencyKey);
    }

    @Transactional(readOnly = true)
    public BusinessDocumentModels.Download download(CurrentAccessContext context, UUID documentId) {
        BusinessDocumentModels.DocumentView document = get(context, documentId);
        if (!BusinessDocumentStatus.GENERATED.name().equals(document.status()) || document.storageObjectKey() == null) throw new IllegalArgumentException("Business document is not available");
        try (InputStream input = storage.open(document.storageObjectKey())) {
            return new BusinessDocumentModels.Download(safeFilename(document), document.contentType(), input.readAllBytes(), document.checksumSha256());
        } catch (IOException exception) { throw new IllegalStateException("Business document download failed", exception); }
    }

    @Transactional
    public BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, String originalFilename, String declaredContentType, byte[] content) {
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD); requireKey(originalFilename); if (content == null || content.length == 0 || content.length > 10485760) throw new IllegalArgumentException("Evidence size is invalid");
        DocumentSubjectType subject = parseSubject(subjectType); DocumentSubjectSnapshot snapshot = subjects.lookup(tenant(context).toString(), workspace(context).toString(), new DocumentSubjectReference(subject, subjectId.toString())); if (!snapshot.subjectExists()) throw new IllegalArgumentException("Evidence subject not found"); authorizeClientScope(context, snapshot.clientAccountId());
        ContentScannerPort.ScanResult scan = scanner.scan(content); String detected = scan.detectedContentType(); if (!scan.clean()) throw new IllegalArgumentException("Evidence rejected: " + scan.reason()); if (!compatible(declaredContentType, detected)) throw new IllegalArgumentException("Evidence MIME type mismatch");
        String key = "evidence/" + tenant(context) + "/" + UUID.randomUUID() + ".bin"; ObjectStoragePort.StoredObject stored = storage.put(key, content, detected); Instant now = Instant.now(); UUID id = UUID.randomUUID();
        jdbc.update("insert into business_documents.object_storage_object (object_key,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,? ,?,?,?,?,?)", key, "nexa-private", stored.checksumSha256(), detected, stored.byteSize(), true, Timestamp.from(now));
        jdbc.update("insert into business_documents.evidence_object (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,declared_content_type,detected_content_type,original_filename,checksum_sha256,byte_size,created_at,scanned_at) values (?,?,?,?,?,?,?,'AVAILABLE',?,?,?,?,?,?,?)", id, tenant(context), workspace(context), uuid(snapshot.clientAccountId()), subject.name(), subjectId, key, declaredContentType, detected, sanitizeFilename(originalFilename), stored.checksumSha256(), stored.byteSize(), Timestamp.from(now), Timestamp.from(now));
        return jdbc.query("select id,subject_type,subject_id,lifecycle_status,declared_content_type,detected_content_type,original_filename,checksum_sha256,byte_size,created_at,scanned_at from business_documents.evidence_object where id=?", (rs, n) -> evidenceView(rs), id).get(0);
    }

    @Transactional
    @Scheduled(fixedDelayString = "${nexa.documents.worker-delay-ms:3000}")
    public void processPendingGenerationRequests() {
        jdbc.update("update business_documents.document_generation_request set status='PENDING',processing_started_at=null,next_attempt_at=current_timestamp where status='PROCESSING' and (processing_started_at is null or processing_started_at < current_timestamp - interval '10 minutes')");
        List<UUID> ids = jdbc.query("select id from business_documents.document_generation_request where status in ('PENDING','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp and requested_at <= current_timestamp order by requested_at,id limit 10", (rs, n) -> rs.getObject(1, UUID.class));
        for (UUID requestId : ids) processOne(requestId);
    }

    private void processOne(UUID requestId) {
        var rows = jdbc.query("select r.id,r.document_id,r.tenant_id,r.workspace_id,r.subject_type,r.subject_id,r.document_type,r.format from business_documents.document_generation_request r where r.id=? and r.status='PENDING'", (rs, n) -> new GenerationRow(rs), requestId); if (rows.isEmpty()) return; GenerationRow request = rows.get(0);
        int claimed = jdbc.update("update business_documents.document_generation_request set status='PROCESSING',attempt_count=attempt_count+1,processing_started_at=current_timestamp where id=? and status in ('PENDING','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp", requestId); if (claimed == 0) return;
        jdbc.update("update business_documents.business_document set status='GENERATING',updated_at=current_timestamp where id=? and status in ('REQUESTED','FAILED')", request.documentId);
        try {
            DocumentSubjectSnapshot subject = subjects.lookup(request.tenantId.toString(), request.workspaceId.toString(), new DocumentSubjectReference(DocumentSubjectType.valueOf(request.subjectType), request.subjectId.toString()));
            Map<String, Object> data = new LinkedHashMap<>(); data.put("subjectType", request.subjectType); data.put("subjectId", request.subjectId.toString()); data.put("lifecycleState", subject.lifecycleState()); data.put("clientAccountId", subject.clientAccountId()); data.put("generatedAt", Instant.now().toString()); data.put("fiscalStatus", request.documentType.contains("DRAFT") ? "NON_FISCAL_DRAFT" : "SERVICE_DOCUMENT");
            DocumentRendererPort.RenderedDocument rendered = renderer.render(BusinessDocumentType.valueOf(request.documentType), BusinessDocumentFormat.valueOf(request.format), data); String key = "documents/" + request.tenantId + "/" + UUID.randomUUID() + "." + rendered.extension(); ObjectStoragePort.StoredObject stored = storage.put(key, rendered.content(), rendered.contentType()); Instant now = Instant.now();
            jdbc.update("insert into business_documents.object_storage_object (object_key,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,?,?,?,?,?,?)", key, "nexa-private", stored.checksumSha256(), stored.contentType(), stored.byteSize(), true, Timestamp.from(now));
            jdbc.update("update business_documents.business_document set status='GENERATED',storage_object_key=?,checksum_sha256=?,content_type=?,byte_size=?,generated_at=?,failure_code=null,failure_detail=null,updated_at=? where id=?", key, stored.checksumSha256(), stored.contentType(), stored.byteSize(), Timestamp.from(now), Timestamp.from(now), request.documentId);
            jdbc.update("update business_documents.business_document old set status='SUPERSEDED',updated_at=current_timestamp where old.tenant_id=? and old.workspace_id=? and old.subject_type=? and old.subject_id=? and old.document_type=? and old.format=? and old.id<>? and old.status='GENERATED'", request.tenantId, request.workspaceId, request.subjectType, request.subjectId, request.documentType, request.format, request.documentId);
            jdbc.update("update business_documents.document_generation_request set status='COMPLETED',last_error=null,processing_started_at=null,completed_at=current_timestamp where id=?", requestId);
        } catch (Exception exception) {
            jdbc.update("update business_documents.business_document set status='FAILED',failure_code='GENERATION_FAILED',failure_detail=?,updated_at=current_timestamp where id=?", truncate(exception.getMessage()), request.documentId);
            jdbc.update("update business_documents.document_generation_request set status=case when attempt_count >= 10 then 'FAILED' else 'PENDING' end,last_error=?,processing_started_at=null,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second'),completed_at=case when attempt_count >= 10 then current_timestamp else null end where id=?", truncate(exception.getMessage()), requestId);
        }
    }

    private BusinessDocumentModels.GenerationRequestView requestView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.GenerationRequestView(rs.getObject("id", UUID.class).toString(), rs.getObject("document_id", UUID.class) == null ? null : rs.getObject("document_id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("document_type"), rs.getString("format"), rs.getString("status"), rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()); }
    private BusinessDocumentModels.DocumentView documentView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.DocumentView(rs.getObject("id", UUID.class).toString(), rs.getObject("client_account_id", UUID.class) == null ? null : rs.getObject("client_account_id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("document_type"), rs.getString("document_number"), rs.getInt("version"), rs.getString("status"), rs.getString("format"), rs.getString("storage_object_key"), rs.getString("checksum_sha256"), rs.getString("content_type"), rs.getObject("byte_size", Long.class) == null ? 0 : rs.getLong("byte_size"), rs.getTimestamp("generated_at") == null ? null : rs.getTimestamp("generated_at").toInstant(), rs.getString("failure_code"), rs.getString("failure_detail"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()); }
    private BusinessDocumentModels.EvidenceView evidenceView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.EvidenceView(rs.getObject("id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("lifecycle_status"), rs.getString("declared_content_type"), rs.getString("detected_content_type"), rs.getString("original_filename"), rs.getString("checksum_sha256"), rs.getObject("byte_size", Long.class) == null ? 0 : rs.getLong("byte_size"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("scanned_at") == null ? null : rs.getTimestamp("scanned_at").toInstant()); }
    private void outbox(CurrentAccessContext context, String type, UUID aggregateId, Map<String, Object> payload) { CanonicalOutbox.append(jdbc, type, "BusinessDocument", aggregateId, tenant(context), workspace(context), Instant.now(), "document-" + aggregateId, null, "1.0", payload); }
    private void read(CurrentAccessContext context) { context.requirePermission(PermissionKey.DOCUMENT_READ); }
    private void requireGeneration(CurrentAccessContext context) { if (context.hasRole(MembershipRole.BUYER)) context.requirePermission(PermissionKey.DOCUMENT_READ); else context.requirePermission(PermissionKey.DOCUMENT_GENERATE); }
    private void authorizeClientScope(CurrentAccessContext context, String clientAccountId) { if (context.hasRole(MembershipRole.BUYER)) { if (clientAccountId == null || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?)", Boolean.class, tenant(context), workspace(context), uuid(clientAccountId), context.membershipId().value()))) throw new IllegalArgumentException("Document is outside buyer scope"); } }
    private boolean authorizedDocument(CurrentAccessContext context, String clientAccountId) { if (!context.hasRole(MembershipRole.BUYER)) return true; return clientAccountId != null && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?)", Boolean.class, tenant(context), workspace(context), uuid(clientAccountId), context.membershipId().value())); }
    private DocumentSubjectType parseSubject(String value) { try { return DocumentSubjectType.valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("Document subject type is invalid", e); } }
    private BusinessDocumentType parseType(String value) { try { return BusinessDocumentType.valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("Document type is invalid", e); } }
    private BusinessDocumentFormat parseFormat(String value) { try { return BusinessDocumentFormat.valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { throw new IllegalArgumentException("Document format is invalid", e); } }
    private static void requireKey(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Idempotency-Key is required"); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static UUID tenant(CurrentAccessContext c) { return c.tenantId().value(); } private static UUID workspace(CurrentAccessContext c) { return c.workspaceId().value(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static String sanitizeFilename(String value) { String safe = value == null ? "evidence.bin" : value.replace('\\', '_').replace('/', '_').replaceAll("[^A-Za-z0-9._-]", "_"); return safe.substring(0, Math.min(255, safe.length())); }
    private static String safeFilename(BusinessDocumentModels.DocumentView d) { return (d.documentType() + "-" + d.subjectId()).replaceAll("[^A-Za-z0-9_-]", "_") + "." + d.format().toLowerCase(Locale.ROOT); }
    private static boolean compatible(String declared, String detected) { return declared != null && detected != null && (declared.equalsIgnoreCase(detected) || (declared.equalsIgnoreCase("image/jpg") && detected.equalsIgnoreCase("image/jpeg"))); }
    private static String truncate(String value) { return value == null ? "unknown" : value.substring(0, Math.min(2000, value.length())); }
    private static String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String idempotencyHash(String value) { return sha256(value); }

    private record RequestClaim(BusinessDocumentModels.GenerationRequestView view, String requestHash) { }
    private record GenerationRow(UUID id, UUID documentId, UUID tenantId, UUID workspaceId, String subjectType, UUID subjectId, String documentType, String format) { GenerationRow(java.sql.ResultSet rs) throws java.sql.SQLException { this(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("document_type"), rs.getString("format")); } }
}
