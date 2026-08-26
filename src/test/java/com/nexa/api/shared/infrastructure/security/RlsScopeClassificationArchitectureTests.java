package com.nexa.api.shared.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RlsScopeClassificationArchitectureTests {
    @Test
    void currentAsIsRegistryNamesEveryRequiredScopeCategoryAndWorkerException() throws Exception {
        String registry = Files.readString(Path.of("docs/security/rls-scope-classification.md"));

        assertThat(registry).contains("TENANT_SCOPED_RLS", "WORKSPACE_SCOPED_RLS", "TENANT_SYSTEM_QUEUE",
                "GLOBAL_IDENTITY", "GLOBAL_REFERENCE", "TECHNICAL_GLOBAL", "NOT_APPLICABLE",
                "USING", "WITH CHECK", "RlsRequestScope", "Blueprint logical TARGET");
        assertThat(registry).contains("integration.outbox_event", "payments.stripe_event_inbox",
                "business_documents.document_generation_request", "iam.security_notification_outbox");
    }
}
