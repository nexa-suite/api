package com.nexa.api.shared.infrastructure.events;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalOutboxEventProcessorArchitectureTests {
    private static final Path PROCESSOR = Path.of(
            "src/main/java/com/nexa/api/shared/infrastructure/events/CanonicalOutboxEventProcessor.java");
    private static final Path ACTOR_ADAPTER = Path.of(
            "src/main/java/com/nexa/api/shared/infrastructure/events/JdbcTenantEventContextQueryAdapter.java");

    @Test
    void processorOwnsOnlyCanonicalOutboxAndInboxPersistence() throws Exception {
        String source = Files.readString(PROCESSOR);

        assertThat(source).contains("integration.outbox_event", "integration.inbox_event");
        assertThat(source).doesNotContain(
                "sales.purchase_request", "sales.sales_order", "sales.client_account_membership",
                "warehouse.inventory_reservation", "logistics.dispatch_order", "tenant_management.",
                "iam.user_account", "payments.receivable", "payments.payment");
    }

    @Test
    void processorUsesPublishedLanguageQueryPortsForContextualReads() throws Exception {
        String source = Files.readString(PROCESSOR);

        assertThat(source).contains(
                "SalesEventContextQueryPort", "WarehouseEventContextQueryPort",
                "LogisticsEventContextQueryPort", "TenantEventContextQueryPort",
                "PaymentEventContextQueryPort");
    }

    @Test
    void workflowActorLookupIsExplicitAndCannotChooseTheFirstMembership() throws Exception {
        String source = Files.readString(ACTOR_ADAPTER);

        assertThat(source).contains(
                "SYSTEM_WORKFLOW_MEMBERSHIP_TYPE", "SYSTEM_WORKFLOW_ROLE_CODE",
                "NEXA_AUTOMATION_IDENTITY", "NEXA_AUTOMATION_EMAIL");
        assertThat(source).doesNotContain("limit 1", "order by m.id");
    }

    @Test
    void accountingDirectionCreatesReceivableOnlyFromInvoiceIssued() throws Exception {
        String source = Files.readString(PROCESSOR);

        assertThat(source).contains("case \"INVOICE_ISSUED\" -> createReceivable(event, payload);");
        String salesOrderBranch = source.substring(source.indexOf("if (\"SALES_ORDER_CONFIRMED\""),
                source.indexOf("if (\"DISPATCH_DELIVERED\""));
        assertThat(salesOrderBranch).doesNotContain("createReceivable");
    }
}
