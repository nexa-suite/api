package com.nexa.api.invoicing.infrastructure.persistence;

import com.nexa.api.invoicing.application.port.BusinessDocumentPort;
import com.nexa.api.invoicing.application.model.BusinessDocumentModels;
import com.nexa.api.invoicing.application.model.BusinessDocumentProjections;
import com.nexa.api.invoicing.application.port.ContentScannerPort;
import com.nexa.api.invoicing.application.port.DocumentRendererPort;
import com.nexa.api.invoicing.application.port.DocumentProjectionLookupPort;
import com.nexa.api.invoicing.application.port.DocumentSubjectLookupPort;
import com.nexa.api.invoicing.application.port.ObjectStoragePort;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentFormat;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentStatus;
import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectSnapshot;
import com.nexa.api.invoicing.domain.model.businessdocument.DocumentSubjectType;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistence adapter for business documents, storage boundary and bounded generation worker. */
@Profile("!test")
@Repository
public class BusinessDocumentService implements BusinessDocumentPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessDocumentService.class);
    private static final String WORKER_LEASE = "current_timestamp + interval '10 minutes'";
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final ContentScannerPort scanner;
    private final DocumentRendererPort renderer;
    private final DocumentSubjectLookupPort subjects;
    private final DocumentProjectionLookupPort projections;
    private final TransactionTemplate transactionTemplate;

    public BusinessDocumentService(JdbcTemplate jdbc, ObjectStoragePort storage, ContentScannerPort scanner, DocumentRendererPort renderer,
            DocumentSubjectLookupPort subjects, DocumentProjectionLookupPort projections, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.storage = storage; this.scanner = scanner; this.renderer = renderer; this.subjects = subjects; this.projections = projections;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Transactional
    public BusinessDocumentModels.GenerationRequestView request(CurrentAccessContext context, String subjectType, UUID subjectId, String documentType, String format, String idempotencyKey) {
        requireGeneration(context);
        requireKey(idempotencyKey);
        DocumentSubjectType subject = parseSubject(subjectType); BusinessDocumentType type = parseType(documentType); BusinessDocumentFormat output = parseFormat(format);
        if (!renderer.supports(type, output)) throw new IllegalArgumentException("Document format is not supported for this document type");
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

    @Transactional(readOnly = true)
    public List<BusinessDocumentModels.DocumentEventView> events(CurrentAccessContext context, UUID documentId) {
        get(context, documentId);
        return jdbc.query("select event_id,event_type,status,occurred_at,processed_at,attempt_count from integration.outbox_event where tenant_id=? and workspace_id=? and aggregate_type='BusinessDocument' and aggregate_id=? order by occurred_at asc,event_id asc",
                (rs, n) -> new BusinessDocumentModels.DocumentEventView(rs.getObject("event_id", UUID.class).toString(), rs.getString("event_type"), rs.getString("status"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant(), rs.getInt("attempt_count")), tenant(context), workspace(context), documentId);
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
        try {
            return new BusinessDocumentModels.Download(safeFilename(document), document.contentType(), storage.open(document.storageObjectKey()), document.byteSize(), document.checksumSha256());
        } catch (RuntimeException exception) { throw new IllegalStateException("Business document download failed", exception); }
    }

    public BusinessDocumentModels.EvidenceView uploadEvidence(CurrentAccessContext context, String subjectType, UUID subjectId,
            String originalFilename, String declaredContentType, byte[] content) {
        String key = sha256((subjectType == null ? "" : subjectType) + subjectId + originalFilename + (content == null ? 0 : content.length));
        requestEvidence(context, subjectType, subjectId, originalFilename, declaredContentType, key);
        return completeEvidence(context, findEvidenceId(context, key), originalFilename, declaredContentType,
                new java.io.ByteArrayInputStream(content == null ? new byte[0] : content), content == null ? 0 : content.length, key);
    }

    @Transactional
    public BusinessDocumentModels.EvidenceView requestEvidence(CurrentAccessContext context, String subjectType, UUID subjectId,
            String originalFilename, String declaredContentType, String idempotencyKey) {
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD);
        requireIdempotencyKey(idempotencyKey);
        String filename = sanitizedRequiredFilename(originalFilename);
        String contentType = requiredContentType(declaredContentType);
        DocumentSubjectType subject = parseSubject(subjectType);
        DocumentSubjectSnapshot snapshot = subjects.lookup(tenant(context).toString(), workspace(context).toString(), new DocumentSubjectReference(subject, subjectId.toString()));
        if (!snapshot.subjectExists()) throw new IllegalArgumentException("Evidence subject not found");
        authorizeClientScope(context, snapshot.clientAccountId());
        List<EvidenceRow> existing = jdbc.query(evidenceSelect() + " where e.tenant_id=? and e.workspace_id=? and e.requested_by_membership_id=? and e.idempotency_key=?",
                (rs, n) -> evidenceRow(rs), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey);
        if (!existing.isEmpty()) return evidenceView(existing.get(0));
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.update("insert into business_documents.evidence_object (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,declared_content_type,original_filename,requested_by_membership_id,idempotency_key,scan_attempt_count,next_scan_at,created_at,updated_at) values (?,?,?,?,?,?,null,'REQUESTED',?,?,?,?,0,current_timestamp,?,?)",
                id, tenant(context), workspace(context), uuid(snapshot.clientAccountId()), subject.name(), subjectId, contentType, filename,
                context.membershipId().value(), idempotencyKey, Timestamp.from(now), Timestamp.from(now));
        return loadEvidenceForWrite(context, id).view();
    }

    public BusinessDocumentModels.EvidenceView completeEvidence(CurrentAccessContext context, UUID evidenceId, String originalFilename,
            String declaredContentType, InputStream content, long contentLength, String idempotencyKey) {
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD);
        requireIdempotencyKey(idempotencyKey);
        String filename = sanitizedRequiredFilename(originalFilename);
        String contentType = requiredContentType(declaredContentType);
        if (content == null || contentLength <= 0 || contentLength > 10485760) throw new IllegalArgumentException("Evidence size is invalid");
        EvidenceAccess access = loadEvidenceForWrite(context, evidenceId);
        EvidenceRow row = access.row();
        if ("AVAILABLE".equals(row.lifecycleStatus()) || "DELETED".equals(row.lifecycleStatus())) return access.view();
        if ("REJECTED".equals(row.lifecycleStatus())) throw new IllegalArgumentException("Evidence was rejected and cannot be completed");
        UUID uploadClaimToken = UUID.randomUUID();
        int claimed = jdbc.update("update business_documents.evidence_object set lifecycle_status='UPLOADING',upload_claim_token=?,upload_lease_until=" + WORKER_LEASE + ",updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status in ('REQUESTED','UPLOADING') and (upload_claim_token is null or upload_lease_until <= current_timestamp)",
                uploadClaimToken, tenant(context), workspace(context), evidenceId);
        if (claimed == 0) return loadEvidenceForWrite(context, evidenceId).view();
        String key = "evidence/quarantine/" + tenant(context) + "/" + evidenceId + "/" + UUID.randomUUID() + ".bin";
        try {
            ObjectStoragePort.StoredObject stored = storage.put(key, content, contentLength, contentType);
            Instant now = Instant.now();
            jdbc.update("insert into business_documents.object_storage_object (object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,?,?,?,?,?,?,?,?)",
                    key, tenant(context), workspace(context), "nexa-private", stored.checksumSha256(), contentType, stored.byteSize(), true, Timestamp.from(now));
            int finalized = jdbc.update("update business_documents.evidence_object set object_key=?,lifecycle_status='QUARANTINED',upload_claim_token=null,upload_lease_until=null,declared_content_type=?,original_filename=?,checksum_sha256=?,byte_size=?,failure_code=null,next_scan_at=current_timestamp,updated_at=? where tenant_id=? and workspace_id=? and id=? and lifecycle_status='UPLOADING' and upload_claim_token=? and upload_lease_until > current_timestamp",
                    key, contentType, filename, stored.checksumSha256(), stored.byteSize(), Timestamp.from(now), tenant(context), workspace(context), evidenceId, uploadClaimToken);
            if (finalized != 1) {
                discardEvidenceObject(key, tenant(context), workspace(context));
                return loadEvidenceForWrite(context, evidenceId).view();
            }
            scanEvidence(context, evidenceId);
            return loadEvidenceForWrite(context, evidenceId).view();
        } catch (RuntimeException exception) {
            jdbc.update("update business_documents.evidence_object set lifecycle_status='REQUESTED',upload_claim_token=null,upload_lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status='UPLOADING' and upload_claim_token=?",
                    tenant(context), workspace(context), evidenceId, uploadClaimToken);
            discardEvidenceObject(key, tenant(context), workspace(context));
            throw exception;
        }
    }

    public BusinessDocumentModels.EvidenceView evidence(CurrentAccessContext context, UUID evidenceId) {
        return loadEvidence(context, evidenceId).view();
    }

    @Transactional(readOnly = true)
    public BusinessDocumentModels.Page<BusinessDocumentModels.EvidenceView> listEvidence(CurrentAccessContext context, String subjectType, UUID subjectId, int page, int size) {
        context.requirePermission(PermissionKey.DOCUMENT_READ);
        int safePage = Math.max(0, page); int safeSize = Math.max(1, Math.min(100, size));
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("e.tenant_id=? and e.workspace_id=? and e.lifecycle_status <> 'DELETED'");
        params.add(tenant(context)); params.add(workspace(context));
        if (subjectType != null && !subjectType.isBlank()) { where.append(" and e.subject_type=?"); params.add(parseSubject(subjectType).name()); }
        if (subjectId != null) { where.append(" and e.subject_id=?"); params.add(subjectId); }
        if (context.hasRole(MembershipRole.BUYER)) {
            where.append(" and exists(select 1 from sales.client_account_membership cam where cam.tenant_id=e.tenant_id and cam.workspace_id=e.workspace_id and cam.client_account_id=e.client_account_id and cam.workspace_membership_id=?)");
            params.add(context.membershipId().value());
        }
        long total = jdbc.queryForObject("select count(*) from business_documents.evidence_object e where " + where, Long.class, params.toArray());
        params.add(safeSize); params.add(safePage * safeSize);
        List<BusinessDocumentModels.EvidenceView> rows = jdbc.query(evidenceSelect() + " where " + where + " order by e.created_at desc,e.id limit ? offset ?",
                (rs, n) -> evidenceView(rs), params.toArray());
        return new BusinessDocumentModels.Page<>(rows, safePage, safeSize, total);
    }

    public BusinessDocumentModels.Download downloadEvidence(CurrentAccessContext context, UUID evidenceId) {
        context.requirePermission(PermissionKey.DOCUMENT_DOWNLOAD);
        EvidenceRow row = loadEvidenceForDownload(context, evidenceId).row();
        if (!"AVAILABLE".equals(row.lifecycleStatus()) || row.objectKey() == null) throw new IllegalArgumentException("Evidence is not available");
        return new BusinessDocumentModels.Download(safeEvidenceFilename(row.originalFilename(), row.id()), row.detectedContentType(), storage.open(row.objectKey()), row.byteSize(), row.checksumSha256());
    }

    public void deleteEvidence(CurrentAccessContext context, UUID evidenceId) {
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD);
        EvidenceRow row = loadEvidenceForWrite(context, evidenceId).row();
        if ("DELETED".equals(row.lifecycleStatus())) return;
        int deleted = jdbc.update("update business_documents.evidence_object set lifecycle_status='DELETED',claim_token=null,lease_until=null,upload_claim_token=null,upload_lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status <> 'DELETED'",
                tenant(context), workspace(context), evidenceId);
        if (deleted == 1 && row.objectKey() != null) storage.delete(row.objectKey());
    }

    @Scheduled(fixedDelayString = "${nexa.documents.worker-delay-ms:3000}")
    public void processPendingEvidenceScans() {
        List<WorkspaceScope> scopes = jdbc.query("select tenant_id,id as workspace_id from tenant_management.workspace order by tenant_id,id",
                (rs, n) -> new WorkspaceScope(rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)));
        for (WorkspaceScope scope : scopes) withScope(scope, () -> {
            List<UUID> work = jdbc.query("select id from business_documents.evidence_object where tenant_id=? and workspace_id=? and lifecycle_status='SCANNING' and scan_attempt_count < 10 and next_scan_at <= current_timestamp and (lease_until is null or lease_until <= current_timestamp) order by created_at,id limit 10",
                    (rs, n) -> rs.getObject("id", UUID.class), scope.tenantId(), scope.workspaceId());
            for (UUID evidenceId : work) scanEvidence(null, evidenceId);
        });
    }

    private void scanEvidence(CurrentAccessContext context, UUID evidenceId) {
        EvidenceRow row = context == null ? loadEvidenceForWorker(evidenceId) : loadEvidenceScoped(context, evidenceId);
        if (row.objectKey() == null || "DELETED".equals(row.lifecycleStatus())) return;
        UUID tenantId = context == null ? workerScope().tenantId() : tenant(context);
        UUID workspaceId = context == null ? workerScope().workspaceId() : workspace(context);
        UUID claimToken = UUID.randomUUID();
        int claimed = jdbc.update("update business_documents.evidence_object set lifecycle_status='SCANNING',scan_attempt_count=scan_attempt_count+1,claim_token=?,lease_until=" + WORKER_LEASE + ",updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status in ('QUARANTINED','SCANNING') and (claim_token is null or lease_until <= current_timestamp) and (next_scan_at is null or next_scan_at <= current_timestamp)",
                claimToken, tenantId, workspaceId, evidenceId);
        if (claimed != 1) return;
        try (InputStream input = storage.open(row.objectKey())) {
            ContentScannerPort.ScanResult scan = scanner.scan(input);
            String detected = scan.detectedContentType();
            if (scan.clean() && compatible(row.declaredContentType(), detected) && extensionCompatible(row.originalFilename(), detected)) {
                jdbc.update("update business_documents.evidence_object set lifecycle_status='AVAILABLE',detected_content_type=?,failure_code=null,scanned_at=current_timestamp,next_scan_at=null,claim_token=null,lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                        detected, tenantId, workspaceId, evidenceId, claimToken);
            } else if (retryableScan(scan)) {
                jdbc.update("update business_documents.evidence_object set lifecycle_status=case when scan_attempt_count >= 10 then 'REJECTED' else 'SCANNING' end,detected_content_type=?,failure_code=?,scanned_at=case when scan_attempt_count >= 10 then current_timestamp else scanned_at end,next_scan_at=case when scan_attempt_count >= 10 then null else current_timestamp + (least(power(2,scan_attempt_count),300) * interval '1 second') end,claim_token=null,lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                        detected, truncate(scan.reason()), tenantId, workspaceId, evidenceId, claimToken);
            } else {
                String failure = !scan.clean() ? scan.reason() : !compatible(row.declaredContentType(), detected) ? "MIME_MISMATCH" : "EXTENSION_MISMATCH";
                jdbc.update("update business_documents.evidence_object set lifecycle_status='REJECTED',detected_content_type=?,failure_code=?,scanned_at=current_timestamp,next_scan_at=null,claim_token=null,lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                        detected, truncate(failure), tenantId, workspaceId, evidenceId, claimToken);
            }
        } catch (Exception exception) {
            jdbc.update("update business_documents.evidence_object set lifecycle_status=case when scan_attempt_count >= 10 then 'REJECTED' else 'SCANNING' end,failure_code=?,next_scan_at=current_timestamp + (least(power(2,scan_attempt_count),300) * interval '1 second'),claim_token=null,lease_until=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                    truncate(exception.getMessage()), tenantId, workspaceId, evidenceId, claimToken);
        }
    }

    @Scheduled(fixedDelayString = "${nexa.documents.worker-delay-ms:3000}")
    public void processPendingGenerationRequests() {
        jdbc.update("update business_documents.document_generation_request set status='PENDING',processing_started_at=null,lease_until=null,claim_token=null,next_attempt_at=current_timestamp where status='PROCESSING' and (lease_until is null or lease_until <= current_timestamp)");
        List<ScopedWork> work = jdbc.query("select id,tenant_id,workspace_id from business_documents.document_generation_request where status in ('PENDING','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp and requested_at <= current_timestamp order by requested_at,id limit 10",
                (rs, n) -> new ScopedWork(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)));
        for (ScopedWork item : work) withScope(item, () -> processOne(item));
    }

    private void processOne(ScopedWork work) {
        UUID claimToken = UUID.randomUUID();
        List<GenerationRow> rows = jdbc.query("select r.id,r.document_id,r.tenant_id,r.workspace_id,r.subject_type,r.subject_id,r.document_type,r.format from business_documents.document_generation_request r where r.id=? and r.tenant_id=? and r.workspace_id=? and r.status in ('PENDING','FAILED')",
                (rs, n) -> new GenerationRow(rs), work.id(), work.tenantId(), work.workspaceId());
        if (rows.isEmpty()) return;
        int claimed = jdbc.update("update business_documents.document_generation_request set status='PROCESSING',attempt_count=attempt_count+1,processing_started_at=current_timestamp,lease_until=" + WORKER_LEASE + ",claim_token=? where id=? and tenant_id=? and workspace_id=? and status in ('PENDING','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp and (lease_until is null or lease_until <= current_timestamp)",
                claimToken, work.id(), work.tenantId(), work.workspaceId());
        if (claimed != 1) return;
        GenerationRow request = rows.get(0);
        String key = null;
        try {
            assertGenerationClaim(request, claimToken);
            int generating = jdbc.update("update business_documents.business_document set status='GENERATING',updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and status in ('REQUESTED','FAILED') and exists(select 1 from business_documents.document_generation_request claim where claim.tenant_id=? and claim.workspace_id=? and claim.id=? and claim.status='PROCESSING' and claim.claim_token=? and claim.lease_until > current_timestamp)",
                    request.tenantId(), request.workspaceId(), request.documentId(), request.tenantId(), request.workspaceId(), request.id(), claimToken);
            if (generating != 1) throw new ClaimLostException();
            DocumentSubjectReference reference = new DocumentSubjectReference(DocumentSubjectType.valueOf(request.subjectType), request.subjectId.toString());
            BusinessDocumentType documentType = BusinessDocumentType.valueOf(request.documentType);
            BusinessDocumentProjections.DocumentProjection projection = projections.lookup(request.tenantId.toString(), request.workspaceId.toString(), reference, documentType);
            DocumentRendererPort.RenderedDocument rendered = renderer.render(projection, BusinessDocumentFormat.valueOf(request.format()));
            key = "documents/" + request.tenantId() + "/" + UUID.randomUUID() + "." + rendered.extension();
            ObjectStoragePort.StoredObject stored = storage.put(key, rendered.content(), rendered.contentType());
            completeGeneration(request, claimToken, key, stored);
        } catch (ClaimLostException exception) {
            discardStoredObject(key);
        } catch (Exception exception) {
            discardStoredObject(key);
            markGenerationFailed(request, claimToken, exception);
        }
    }

    private void completeGeneration(GenerationRow request, UUID claimToken, String key, ObjectStoragePort.StoredObject stored) {
        inTransaction(() -> {
            assertGenerationClaim(request, claimToken);
            Instant now = Instant.now();
            jdbc.update("insert into business_documents.object_storage_object (object_key,tenant_id,workspace_id,bucket_name,checksum_sha256,content_type,byte_size,private_object,created_at) values (?,?,?,?,?,?,?,?,?)",
                    key, request.tenantId(), request.workspaceId(), "nexa-private", stored.checksumSha256(), stored.contentType(), stored.byteSize(), true, Timestamp.from(now));
            int generated = jdbc.update("update business_documents.business_document set status='GENERATED',storage_object_key=?,checksum_sha256=?,content_type=?,byte_size=?,generated_at=?,failure_code=null,failure_detail=null,updated_at=? where tenant_id=? and workspace_id=? and id=? and status='GENERATING' and exists(select 1 from business_documents.document_generation_request claim where claim.tenant_id=? and claim.workspace_id=? and claim.id=? and claim.status='PROCESSING' and claim.claim_token=? and claim.lease_until > current_timestamp)",
                    key, stored.checksumSha256(), stored.contentType(), stored.byteSize(), Timestamp.from(now), Timestamp.from(now), request.tenantId(), request.workspaceId(), request.documentId(), request.tenantId(), request.workspaceId(), request.id(), claimToken);
            if (generated != 1) throw new ClaimLostException();
            jdbc.update("update business_documents.business_document old set status='SUPERSEDED',updated_at=current_timestamp where old.tenant_id=? and old.workspace_id=? and old.subject_type=? and old.subject_id=? and old.document_type=? and old.format=? and old.id<>? and old.status='GENERATED' and exists(select 1 from business_documents.document_generation_request claim where claim.tenant_id=? and claim.workspace_id=? and claim.id=? and claim.status='PROCESSING' and claim.claim_token=? and claim.lease_until > current_timestamp)",
                    request.tenantId(), request.workspaceId(), request.subjectType(), request.subjectId(), request.documentType(), request.format(), request.documentId(), request.tenantId(), request.workspaceId(), request.id(), claimToken);
            int completed = jdbc.update("update business_documents.document_generation_request set status='COMPLETED',last_error=null,processing_started_at=null,lease_until=null,claim_token=null,completed_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    request.tenantId(), request.workspaceId(), request.id(), claimToken);
            if (completed != 1) throw new ClaimLostException();
        });
    }

    private void markGenerationFailed(GenerationRow request, UUID claimToken, Exception exception) {
        inTransaction(() -> {
            String failure = truncate(exception.getMessage());
            jdbc.update("update business_documents.business_document set status='FAILED',failure_code='GENERATION_FAILED',failure_detail=?,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and status='GENERATING' and exists(select 1 from business_documents.document_generation_request claim where claim.tenant_id=? and claim.workspace_id=? and claim.id=? and claim.status='PROCESSING' and claim.claim_token=? and claim.lease_until > current_timestamp)",
                    failure, request.tenantId(), request.workspaceId(), request.documentId(), request.tenantId(), request.workspaceId(), request.id(), claimToken);
            jdbc.update("update business_documents.document_generation_request set status=case when attempt_count >= 10 then 'FAILED' else 'PENDING' end,last_error=?,processing_started_at=null,lease_until=null,claim_token=null,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second'),completed_at=case when attempt_count >= 10 then current_timestamp else null end where tenant_id=? and workspace_id=? and id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    failure, request.tenantId(), request.workspaceId(), request.id(), claimToken);
        });
    }

    private void assertGenerationClaim(GenerationRow request, UUID claimToken) {
        Boolean owner = jdbc.queryForObject("select exists(select 1 from business_documents.document_generation_request where tenant_id=? and workspace_id=? and id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp)", Boolean.class,
                request.tenantId(), request.workspaceId(), request.id(), claimToken);
        if (!Boolean.TRUE.equals(owner)) throw new ClaimLostException();
    }

    private void inTransaction(Runnable action) {
        if (transactionTemplate == null) action.run();
        else transactionTemplate.executeWithoutResult(status -> action.run());
    }

    private BusinessDocumentModels.GenerationRequestView requestView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.GenerationRequestView(rs.getObject("id", UUID.class).toString(), rs.getObject("document_id", UUID.class) == null ? null : rs.getObject("document_id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("document_type"), rs.getString("format"), rs.getString("status"), rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()); }
    private BusinessDocumentModels.DocumentView documentView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.DocumentView(rs.getObject("id", UUID.class).toString(), rs.getObject("client_account_id", UUID.class) == null ? null : rs.getObject("client_account_id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("document_type"), rs.getString("document_number"), rs.getInt("version"), rs.getString("status"), rs.getString("format"), rs.getString("storage_object_key"), rs.getString("checksum_sha256"), rs.getString("content_type"), rs.getObject("byte_size", Long.class) == null ? 0 : rs.getLong("byte_size"), rs.getTimestamp("generated_at") == null ? null : rs.getTimestamp("generated_at").toInstant(), rs.getString("failure_code"), rs.getString("failure_detail"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()); }
    private BusinessDocumentModels.EvidenceView evidenceView(java.sql.ResultSet rs) throws java.sql.SQLException { return new BusinessDocumentModels.EvidenceView(rs.getObject("id", UUID.class).toString(), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class).toString(), rs.getString("lifecycle_status"), rs.getString("declared_content_type"), rs.getString("detected_content_type"), rs.getString("original_filename"), rs.getString("checksum_sha256"), rs.getObject("byte_size", Long.class) == null ? 0 : rs.getLong("byte_size"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("scanned_at") == null ? null : rs.getTimestamp("scanned_at").toInstant(), rs.getString("failure_code"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()); }
    private BusinessDocumentModels.EvidenceView evidenceView(EvidenceRow row) { return new BusinessDocumentModels.EvidenceView(row.id().toString(), row.subjectType(), row.subjectId().toString(), row.lifecycleStatus(), row.declaredContentType(), row.detectedContentType(), row.originalFilename(), row.checksumSha256(), row.byteSize(), row.createdAt(), row.scannedAt(), row.failureCode(), row.updatedAt()); }
    private String evidenceSelect() { return "select e.id,e.client_account_id,e.subject_type,e.subject_id,e.object_key,e.lifecycle_status,e.declared_content_type,e.detected_content_type,e.original_filename,e.checksum_sha256,e.byte_size,e.created_at,e.scanned_at,e.failure_code,e.updated_at from business_documents.evidence_object e"; }
    private EvidenceAccess loadEvidence(CurrentAccessContext context, UUID evidenceId) { context.requirePermission(PermissionKey.DOCUMENT_READ); return evidenceAccess(loadEvidenceScoped(context, evidenceId)); }
    private EvidenceAccess loadEvidenceForWrite(CurrentAccessContext context, UUID evidenceId) { context.requirePermission(PermissionKey.DOCUMENT_UPLOAD); return evidenceAccess(loadEvidenceScoped(context, evidenceId)); }
    private EvidenceAccess loadEvidenceForDownload(CurrentAccessContext context, UUID evidenceId) { return evidenceAccess(loadEvidenceScoped(context, evidenceId)); }
    private EvidenceAccess evidenceAccess(EvidenceRow row) { return new EvidenceAccess(row, evidenceView(row)); }
    private EvidenceRow loadEvidenceScoped(CurrentAccessContext context, UUID evidenceId) {
        EvidenceRow row = jdbc.query(evidenceSelect() + " where e.tenant_id=? and e.workspace_id=? and e.id=?", (rs, n) -> evidenceRow(rs), tenant(context), workspace(context), evidenceId)
                .stream().filter(value -> authorizedDocument(context, value.clientAccountId() == null ? null : value.clientAccountId().toString())).findFirst().orElseThrow(() -> new IllegalArgumentException("Evidence not found"));
        return row;
    }
    private EvidenceRow loadEvidenceForWorker(UUID evidenceId) {
        RlsRequestScope.Scope scope = workerScope();
        return jdbc.query(evidenceSelect() + " where e.tenant_id=? and e.workspace_id=? and e.id=?", (rs, n) -> evidenceRow(rs), scope.tenantId(), scope.workspaceId(), evidenceId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Evidence not found"));
    }
    private UUID findEvidenceId(CurrentAccessContext context, String idempotencyKey) {
        return jdbc.query(evidenceSelect() + " where e.tenant_id=? and e.workspace_id=? and e.requested_by_membership_id=? and e.idempotency_key=?", (rs, n) -> rs.getObject("id", UUID.class), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Evidence request not found"));
    }
    private EvidenceRow evidenceRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new EvidenceRow(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("object_key"), rs.getString("lifecycle_status"), rs.getString("declared_content_type"), rs.getString("detected_content_type"), rs.getString("original_filename"), rs.getString("checksum_sha256"), rs.getObject("byte_size", Long.class) == null ? 0 : rs.getLong("byte_size"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("scanned_at") == null ? null : rs.getTimestamp("scanned_at").toInstant(), rs.getString("failure_code"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()); }
    private static String safeEvidenceFilename(String original, UUID id) { return id + "-" + sanitizeFilename(original); }
    private static String sanitizedRequiredFilename(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Evidence filename is required"); String safe = sanitizeFilename(value); if (safe.isBlank() || safe.equals(".")) throw new IllegalArgumentException("Evidence filename is invalid"); return safe; }
    private static String requiredContentType(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Evidence content type is required"); return value.trim().toLowerCase(Locale.ROOT); }
    private static void requireIdempotencyKey(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Idempotency-Key is required"); }
    private static boolean extensionCompatible(String filename, String detected) { String lower = filename.toLowerCase(Locale.ROOT); return ("image/png".equalsIgnoreCase(detected) && lower.endsWith(".png")) || ("image/jpeg".equalsIgnoreCase(detected) && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) || ("application/pdf".equalsIgnoreCase(detected) && lower.endsWith(".pdf")); }
    private static boolean retryableScan(ContentScannerPort.ScanResult scan) {
        if (scan.reason() == null) return false;
        String reason = scan.reason().toUpperCase(Locale.ROOT);
        return reason.equals("MALWARE_SCANNER_UNAVAILABLE")
                || reason.equals("MALWARE_SCANNER_TIMEOUT")
                || reason.equals("MALFORMED_SCANNER_RESPONSE");
    }
    private record EvidenceAccess(EvidenceRow row, BusinessDocumentModels.EvidenceView view) { }
    private record EvidenceRow(UUID id, UUID clientAccountId, String subjectType, UUID subjectId, String objectKey, String lifecycleStatus,
            String declaredContentType, String detectedContentType, String originalFilename, String checksumSha256, long byteSize,
            Instant createdAt, Instant scannedAt, String failureCode, Instant updatedAt) { }
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
    private void discardStoredObject(String key) {
        if (key == null) return;
        try { storage.delete(key); }
        catch (RuntimeException exception) { LOGGER.warn("Business document object cleanup failed", exception); }
    }
    private void discardEvidenceObject(String key, UUID tenantId, UUID workspaceId) {
        try { jdbc.update("delete from business_documents.object_storage_object where object_key=? and tenant_id=? and workspace_id=?", key, tenantId, workspaceId); }
        catch (RuntimeException exception) { LOGGER.warn("Evidence metadata cleanup failed", exception); }
        discardStoredObject(key);
    }
    private RlsRequestScope.Scope workerScope() {
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null) throw new IllegalStateException("Document worker scope is required");
        return scope;
    }
    private static void withScope(ScopedWork work, Runnable action) { RlsRequestScope.set(work.tenantId(), work.workspaceId()); try { action.run(); } finally { RlsRequestScope.clear(); } }
    private static void withScope(WorkspaceScope scope, Runnable action) { RlsRequestScope.set(scope.tenantId(), scope.workspaceId()); try { action.run(); } finally { RlsRequestScope.clear(); } }

    private record RequestClaim(BusinessDocumentModels.GenerationRequestView view, String requestHash) { }
    private record ScopedWork(UUID id, UUID tenantId, UUID workspaceId) { }
    private record WorkspaceScope(UUID tenantId, UUID workspaceId) { }
    private record GenerationRow(UUID id, UUID documentId, UUID tenantId, UUID workspaceId, String subjectType, UUID subjectId, String documentType, String format) { GenerationRow(java.sql.ResultSet rs) throws java.sql.SQLException { this(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("document_type"), rs.getString("format")); } }
    private static final class ClaimLostException extends RuntimeException { }
}
