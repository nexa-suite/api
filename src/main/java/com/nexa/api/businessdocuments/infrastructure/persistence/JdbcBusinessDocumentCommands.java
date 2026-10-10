package com.nexa.api.businessdocuments.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** BC-09 command adapter for payment-receipt generation requests on the caller's JDBC transaction. */
public final class JdbcBusinessDocumentCommands implements BusinessDocumentCommands {
    private final JdbcTemplate jdbc;
    private final CanonicalOutboxPort canonicalOutbox;

    public JdbcBusinessDocumentCommands(JdbcTemplate jdbc, CanonicalOutboxPort canonicalOutbox) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC session is required");
        this.canonicalOutbox = Objects.requireNonNull(canonicalOutbox, "Canonical outbox is required");
    }

    @Override
    public void enqueuePaymentReceipt(UUID tenantId, UUID workspaceId, UUID paymentId,
                                     UUID clientAccountId, UUID requestedByMembershipId,
                                     String eventKey, Instant now) {
        if (tenantId == null || workspaceId == null || paymentId == null || clientAccountId == null
                || requestedByMembershipId == null || eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("Payment receipt document request is incomplete");
        }
        Instant occurredAt = now == null ? Instant.now() : now;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, n) -> rs.getObject(1),
                tenantId + "|" + workspaceId + "|payment-receipt-document|" + paymentId);
        UUID documentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Integer version = jdbc.queryForObject("select coalesce(max(version),0)+1 from business_documents.business_document "
                        + "where tenant_id=? and workspace_id=? and subject_type='PAYMENT' and subject_id=? "
                        + "and document_type='PAYMENT_RECEIPT' and format='PDF'",
                Integer.class, tenantId, workspaceId, paymentId);
        int inserted = jdbc.update("insert into business_documents.business_document "
                        + "(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,created_at,updated_at) "
                        + "values (?,?,?,?, 'PAYMENT',?,'PAYMENT_RECEIPT',?,'REQUESTED','PDF',?,?) on conflict do nothing",
                documentId, tenantId, workspaceId, clientAccountId, paymentId, version,
                Timestamp.from(occurredAt), Timestamp.from(occurredAt));
        if (inserted == 0) return;
        jdbc.update("insert into business_documents.document_generation_request "
                        + "(id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,requested_at) "
                        + "values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?) on conflict do nothing",
                requestId, tenantId, workspaceId, requestedByMembershipId, documentId, "PAYMENT", paymentId,
                "PAYMENT_RECEIPT", "PDF", "payment-receipt-" + paymentId, sha256(eventKey), Timestamp.from(occurredAt));
        canonicalOutbox.append("BUSINESS_DOCUMENT_GENERATION_REQUESTED", "BusinessDocument", documentId,
                tenantId, workspaceId, occurredAt, "payment-receipt-" + paymentId, null, "1.0",
                "payment-receipt-" + paymentId,
                Map.of("documentId", documentId, "requestId", requestId, "subjectType", "PAYMENT",
                        "subjectId", paymentId, "documentType", "PAYMENT_RECEIPT", "format", "PDF"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
