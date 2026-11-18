package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BankTransferReviewIT extends PaymentIntegrationSupport {
    @Test
    void bankTransferMayBeReportedAndApprovedWithoutAttachment() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "bank-transfer-no-proof-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BT-NO-PROOF-" + uuid() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String paymentId = json(created).get("id").asText();
        assertThat(json(created).get("status").asText()).isEqualTo("PROCESSING");

        MvcResult approved = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/approve")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", "bank-approve-no-proof-" + uuid()))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(approved).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(tenantJdbc().queryForObject("select bank_transfer_proof_evidence_id from payments.payment where id=?", UUID.class, UUID.fromString(paymentId))).isNull();
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?", BigDecimal.class, receivable.id()))
                .isEqualByComparingTo(receivable.amount());
    }

    @Test
    void financeCanListPendingTransfersButBuyerCannotUseTheReviewQueue() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String reference = "BT-LIST-" + uuid();
        mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "bank-transfer-list-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isCreated());

        MvcResult listed = mockMvc.perform(get("/api/v1/payments")
                        .param("method", "BANK_TRANSFER").param("status", "PROCESSING")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(listed).get("items").toString()).contains(reference);

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isForbidden());
    }

    @Test
    void bankTransferStaysPendingUntilApproveAndReplayIsIdempotent() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String proof = uploadAvailableProof(owner, receivable.id().toString());
        String createKey = "bank-transfer-" + uuid();
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BT-" + uuid() + "\",\"proofEvidenceId\":\"" + proof + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String paymentId = json(created).get("id").asText();
        assertThat(json(created).get("status").asText()).isEqualTo("PROCESSING");

        String reviewKey = "bank-review-" + uuid();
        MvcResult approved = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/approve")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", reviewKey))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/approve")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", reviewKey))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(approved).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(json(replay).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?", java.math.BigDecimal.class, receivable.id()))
                .isEqualByComparingTo(receivable.amount());
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.receivable_allocation where payment_id=?", Integer.class, UUID.fromString(paymentId))).isEqualTo(1);
    }

    @Test
    void buyerCanUploadOwnReceivableProofAndPaymentRemainsPendingUntilReview() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String proofKey = "buyer-transfer-proof-" + uuid();
        String proof = uploadAvailableProof(buyer, receivable.id().toString(), proofKey);
        String proofReplay = uploadAvailableProof(buyer, receivable.id().toString(), proofKey);
        assertThat(proofReplay).isEqualTo(proof);

        MvcResult evidence = mockMvc.perform(get("/api/v1/business-document-evidence/" + proof)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(evidence).get("lifecycleStatus").asText()).isEqualTo("AVAILABLE");

        String createKey = "buyer-bank-transfer-" + uuid();
        String reference = "BT-BUYER-" + uuid();
        String request = "{\"reference\":\"" + reference + "\",\"proofEvidenceId\":\"" + proof + "\"}";
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();

        String paymentId = json(created).get("id").asText();
        assertThat(paymentId).isEqualTo(json(replay).get("id").asText());
        assertThat(json(created).get("status").asText()).isEqualTo("PROCESSING");
        assertThat(tenantJdbc().queryForObject(
                "select bank_transfer_proof_evidence_id from payments.payment where id=?",
                UUID.class, UUID.fromString(paymentId))).isEqualTo(UUID.fromString(proof));
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?",
                BigDecimal.class, receivable.id())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void buyerCannotUploadProofForReceivableOutsideCurrentTenantWorkspace() throws Exception {
        ForeignReceivable foreign = createForeignReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "foreign-proof-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"RECEIVABLE\",\"subjectId\":\""
                                + foreign.receivableId() + "\",\"originalFilename\":\"proof.png\","
                                + "\"declaredContentType\":\"image/png\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void bankTransferRejectsEvidenceStillInQuarantine() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult requested = mockMvc.perform(post("/api/v1/business-document-evidence/requests")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "quarantined-proof-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"RECEIVABLE\",\"subjectId\":\""
                                + receivable.id() + "\",\"originalFilename\":\"proof.png\","
                                + "\"declaredContentType\":\"image/png\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID evidenceId = UUID.fromString(json(requested).get("id").asText());
        tenantJdbc().update("update business_documents.evidence_object set lifecycle_status='QUARANTINED' "
                + "where tenant_id=? and workspace_id=? and id=?", tenantUuid(), workspaceUuid(), evidenceId);

        mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "quarantined-payment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BT-QUARANTINED-" + uuid()
                                + "\",\"proofEvidenceId\":\"" + evidenceId + "\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.payment where receivable_id=? "
                + "and method='BANK_TRANSFER'", Integer.class, receivable.id())).isZero();
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?",
                BigDecimal.class, receivable.id())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void bankTransferCanBeRejectedWithAuditedReason() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String proof = uploadAvailableProof(owner, receivable.id().toString());
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "bank-transfer-reject-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BT-REJECT-" + uuid() + "\",\"proofEvidenceId\":\"" + proof + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String paymentId = json(created).get("id").asText();

        MvcResult rejected = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/reject")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", "bank-reject-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Reference could not be reconciled\"}"))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(rejected).get("status").asText()).isEqualTo("FAILED");
        assertThat(tenantJdbc().queryForObject("select amount_paid from payments.receivable where id=?", java.math.BigDecimal.class, receivable.id()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tenantJdbc().queryForObject("select review_reason from payments.payment where id=?", String.class, UUID.fromString(paymentId)))
                .isEqualTo("Reference could not be reconciled");
    }

    @Test
    void bankTransferCreateRejectsIdempotencyPayloadConflictForReferenceOrProof() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String key = "bank-transfer-payload-conflict-" + uuid();
        mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"BT-FIRST-" + uuid() + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"BT-SECOND-" + uuid() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void bankTransferReviewRejectsSameKeyWithDifferentActionOrReason() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult created = mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/bank-transfer-payments")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "bank-review-conflict-create-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"BT-REVIEW-CONFLICT-" + uuid() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String paymentId = json(created).get("id").asText();
        String reviewKey = "bank-review-conflict-" + uuid();

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/approve")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", reviewKey))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/bank-transfer/reject")
                        .header("Authorization", "Bearer " + owner).header("Idempotency-Key", reviewKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Different review payload\"}"))
                .andExpect(status().isConflict());
    }
}
