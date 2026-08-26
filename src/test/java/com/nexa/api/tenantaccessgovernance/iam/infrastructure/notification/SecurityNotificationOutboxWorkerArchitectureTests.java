package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityNotificationOutboxWorkerArchitectureTests {
    private static final Path WORKER = Path.of(
            "src/main/java/com/nexa/api/tenantaccessgovernance/iam/infrastructure/notification/SecurityNotificationOutboxWorker.java");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V87__close_foundation_claim_fencing.sql");
    private static final Path RETRY_MIGRATION = Path.of(
            "src/main/resources/db/migration/V88__durable_reconciliation_retry_idempotency.sql");

    @Test
    void notificationDeliveryUsesLeaseFencingAndStableProviderIdentity() throws Exception {
        String source = Files.readString(WORKER);

        assertThat(source).contains("claim_token", "lease_until", "delivery_key",
                "row.claimToken()", "row.deliveryKey()",
                "status='PROCESSING' and claim_token=? and lease_until > current_timestamp");
        assertThat(source).doesNotContain("processing_started_at < current_timestamp - interval '10 minutes'");
    }

    @Test
    void migrationAddsDurableClaimsToTheExistingSecurityQueue() throws Exception {
        String migration = Files.readString(MIGRATION);

        assertThat(migration).contains("iam.security_notification_outbox",
                "ADD COLUMN IF NOT EXISTS lease_until", "ADD COLUMN IF NOT EXISTS claim_token",
                "ix_security_outbox_lease_queue");
    }

    @Test
    void reconciliationRetryIdempotencyIsDurableAndScoped() throws Exception {
        String migration = Files.readString(RETRY_MIGRATION);

        assertThat(migration).contains("reconciliation_refund_idempotency", "request_hash", "result_json",
                "FORCE ROW LEVEL SECURITY", "USING", "WITH CHECK", "IN_PROGRESS", "SUCCESS", "FAILURE");
    }
}
