package com.nexa.api.payments.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTransactionBoundaryArchitectureTests {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/nexa/api/payments/infrastructure/persistence/PaymentService.java");

    @Test
    void stripeCreateIsOutsideTheDatabaseTransaction() throws Exception {
        String source = Files.readString(SERVICE);
        String createMethod = between(source, "public PaymentModels.PaymentIntentView createCardPaymentIntent",
                "/**\n     * Local-only browser acceptance seam");

        assertThat(createMethod).doesNotContain("@Transactional");
        assertThat(createMethod).contains("transactionTemplate.execute", "stripe.createPaymentIntent",
                "persistProviderIntent");

        String persistenceMethod = between(source, "private PaymentRow persistProviderIntent",
                "private void recordProviderFailure");
        assertThat(persistenceMethod).doesNotContain("stripe.");
    }

    @Test
    void inboxFinalizationCarriesTheClaimTokenIntoTheShortTransaction() throws Exception {
        String source = Files.readString(SERVICE);
        String worker = between(source, "public void processStripeWebhookInbox()", "private void registerInboxGauges");

        assertThat(worker).contains("transactionTemplate.executeWithoutResult", "assertInboxClaim",
                "processWebhook(item.eventId(), claimToken)",
                "status='PROCESSING' and claim_token=? and lease_until > current_timestamp");
    }

    @Test
    void reconciliationRefundProviderCallIsOutsideTheDatabaseTransaction() throws Exception {
        String source = Files.readString(SERVICE);
        String refund = between(source, "private void executeReconciliationRefund", "private void finalizeReconciliationRefund");

        assertThat(refund).contains("stripe.refundPayment", "transactionTemplate.executeWithoutResult");
        assertThat(refund).doesNotContain("@Transactional");
        assertThat(source).contains("REFUND_PROCESSING", "claim_token=?", "lease_until > current_timestamp",
                "reconciliationRefundKey(work.caseId())", "refund.status()", "result_json", "request_hash");
        assertThat(source).doesNotContain("\"nexa-reconciliation-refund-\" + payment.id()");
    }

    @Test
    void reconciliationWorkerUsesKeysetWorkspacePaginationAndManualRetriesAreBounded() throws Exception {
        String source = Files.readString(SERVICE);

        assertThat(source).contains("(tenant_id,id) > (?,?)", "MAX_RECONCILIATION_ATTEMPTS",
                "operator override is required", "result_json is null");
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V87__close_foundation_claim_fencing.sql")))
                .doesNotContain("UPDATE payments.payment_reconciliation_case");
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).as("source marker: %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("source marker: %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
