package com.nexa.api.bootstrap.runtime;

import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentServiceFactory;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundBusinessDocumentPort;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.events.CanonicalOutbox;
import com.nexa.api.bootstrap.runtime.events.TenantPurchaseRequestApprovedConsumer;
import com.nexa.api.bootstrap.runtime.boundaries.payments.TenantPaymentPersistenceAdapter;
import com.nexa.api.payments.tenantdatabase.TenantPaymentSessionFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that every local Tenant capability composes without a central document-command stub. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@TestPropertySource(properties = {
        "nexa.tenant-business.buyer-wallet-read.enabled=true",
        "nexa.tenant-business.buyer-wallet-recharge.enabled=true",
        "nexa.tenant-business.purchase-request.enabled=true",
        "nexa.tenant-business.catalog-detail-read.enabled=true",
        "nexa.tenant-business.catalog-management.enabled=true",
        "nexa.tenant-business.payments.enabled=true",
        "nexa.tenant-business.fulfillment-delivery.enabled=true",
        "nexa.tenant-business.warehouse-operations.enabled=true",
        "nexa.tenant-business.local-fixtures.enabled=true",
        "nexa.tenant-business.support-sales-order-read.enabled=true",
        "nexa.tenant-business.business-documents.enabled=true",
        "nexa.tenant-business.business-traceability.enabled=true",
        "nexa.tenant-business.notifications.enabled=true",
        "nexa.business-traceability.worker-delay-ms=3600000"
})
class TenantBusinessCapabilitiesContextIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private TenantBusinessDocumentWorkerScopeQuery workerScopes;

    @Autowired
    private TenantBusinessTraceabilityWorkerRouter traceabilityWorkerRouter;

    @Autowired
    private TenantBusinessTraceabilityBindingsFactory traceabilityBindings;

    @Autowired
    private TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;

    @Autowired
    private TenantPurchaseRequestApprovedConsumer approvedConsumer;

    @Test
    void allTenantCapabilitiesComposeWithoutCentralDocumentCommands() {
        assertThat(context.getBean(TenantPaymentPersistenceAdapter.class)).isNotNull();
        assertThat(context.getBean(TenantBoundBusinessDocumentPort.class)).isNotNull();
        assertThat(context.getBean(TenantPaymentSessionFactory.class)).isNotNull();
        assertThat(context.getBean(TenantBusinessDocumentServiceFactory.class)).isNotNull();
        assertThat(context.getBeansOfType(BusinessDocumentCommands.class)).isEmpty();

        var expectedScope = new TenantBusinessDocumentWorkerScopeQuery.Scope(
                UUID.fromString(tenantId()), UUID.fromString(workspaceId()));
        assertThat(workerScopes.listReadyWorkspaces(null, null, 10)).contains(expectedScope);
        assertThat(workerScopes.isReadyWorkspace(expectedScope.tenantId(), expectedScope.workspaceId())).isTrue();

        assertThat(scopeReaderHasColumnPrivilege("id")).isTrue();
        assertThat(scopeReaderHasColumnPrivilege("workspace_id")).isTrue();
        assertThat(scopeReaderHasColumnPrivilege("user_id")).isTrue();
		assertThat(scopeReaderHasColumnPrivilege("membership_type")).isFalse();
        assertThat(scopeReaderHasColumnPrivilege("status")).isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege("
                + "'nexa_business_documents_scope_reader', 'tenant_management.workspace_membership', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select not has_table_privilege("
                + "'nexa_business_documents_scope_reader', 'sales.client_account', 'SELECT') "
                + "and not has_table_privilege('nexa_business_documents_scope_reader', "
                + "'payments.credit_account', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select rolcanlogin and not rolinherit and not rolbypassrls "
                + "and not exists (select 1 from pg_auth_members where member=pg_roles.oid or roleid=pg_roles.oid) "
                + "from pg_roles where rolname='nexa_business_documents_scope_reader'", Boolean.class)).isTrue();
    }

    @Test
    void backgroundSchedulersRemainDisabledDuringIntegrationTests() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void dedicatedTraceabilityWorkerCanRunScopedOutboxRowLockQueries() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID otherWorkspace = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String eventType = "BusinessFactTraced.v1";
        Instant occurredAt = Instant.now();
        Instant createdAt = occurredAt.plusSeconds(86_400);
        JdbcTemplate tenantTestJdbc = tenantJdbcFor(tenant);
        tenantTestJdbc.update("insert into integration.outbox_event "
                        + "(event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,"
                        + "correlation_id,schema_version,payload,created_at) values (?,?,?,?,?,?,?,?,?,?::jsonb,?)",
                eventId, eventType, "BusinessTraceabilityRecord", aggregateId, tenant, workspace,
                Timestamp.from(occurredAt), "tenant-lock-privilege-regression", "1", "{}",
                Timestamp.from(createdAt));

        try {
            var pendingRows = traceabilityWorkerRouter.inTransaction(tenant, workspace, (tenantJdbc, ignored) -> {
                var worker = traceabilityBindings.bindWorker(tenantJdbc).worker();
                var businessFacts = worker.findPendingBusinessFacts(tenant, workspace, 10, null, null);
                var ownerEvents = worker.findPendingForConsumer(tenant, workspace, "lock-privilege-regression",
                        Set.of(eventType), 10, createdAt.minusSeconds(1), new UUID(0, 0));
                var lockedOwnerEventIds = tenantJdbc.queryForList("select event_id from integration.outbox_event "
                                + "where event_id=? and tenant_id=? and workspace_id=? for update skip locked",
                        UUID.class, eventId, tenant, workspace);
                var otherWorkspaceEventIds = tenantJdbc.queryForList("select event_id from integration.outbox_event "
                                + "where event_id=? and tenant_id=? and workspace_id=? for update skip locked",
                        UUID.class, eventId, tenant, otherWorkspace);
                boolean minimalPrivilege = Boolean.TRUE.equals(tenantJdbc.queryForObject(
                        "select current_user='nexa_business_traceability_worker' "
                                + "and has_column_privilege(current_user,'integration.outbox_event','event_id','UPDATE') "
                                + "and not has_column_privilege(current_user,'integration.outbox_event','payload','UPDATE') "
                                + "and not has_table_privilege(current_user,'integration.outbox_event','UPDATE') "
                                + "and exists (select 1 from pg_roles where rolname=current_user and rolcanlogin "
                                + "and not rolinherit and not rolsuper and not rolcreatedb and not rolcreaterole "
                                + "and not rolreplication and not rolbypassrls) "
                                + "and not exists (select 1 from pg_auth_members membership join pg_roles role "
                                + "on membership.member=role.oid or membership.roleid=role.oid "
                                + "where role.rolname=current_user)", Boolean.class));
                boolean materialChangeExpiryPrivilege = Boolean.TRUE.equals(tenantJdbc.queryForObject(
                        "select current_user='nexa_business_traceability_worker' "
                                + "and current_setting('app.current_tenant_id', true)=? "
                                + "and current_setting('app.current_workspace_id', true)=? "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','tenant_id','SELECT') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','workspace_id','SELECT') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','purchase_request_id','SELECT') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','status','SELECT') "
                                + "and not has_table_privilege(current_user,'sales.purchase_request_material_change','SELECT') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','status','UPDATE') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','resolved_at','UPDATE') "
                                + "and has_column_privilege(current_user,'sales.purchase_request_material_change','resolved_request_version','UPDATE') "
                                + "and not has_table_privilege(current_user,'sales.purchase_request_material_change','UPDATE') "
                                + "and not has_table_privilege(current_user,'sales.purchase_request_material_change','DELETE') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','original_snapshot','SELECT') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','proposed_snapshot','SELECT') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','proposal_reason','SELECT') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','original_snapshot','UPDATE') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','proposed_snapshot','UPDATE') "
                                + "and not has_column_privilege(current_user,'sales.purchase_request_material_change','proposal_reason','UPDATE') "
                                + "and exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                                + "where n.nspname='sales' and c.relname='purchase_request_material_change' "
                                + "and c.relrowsecurity and c.relforcerowsecurity) "
                                + "and exists (select 1 from pg_policies where schemaname='sales' "
                                + "and tablename='purchase_request_material_change' "
                                + "and policyname='v104_pr_material_change_scope' "
                                + "and qual like '%app.current_tenant_id%' and qual like '%app.current_workspace_id%' "
                                + "and with_check like '%app.current_tenant_id%' and with_check like '%app.current_workspace_id%')",
                        Boolean.class, tenant.toString(), workspace.toString()));
                boolean notificationPushPrivilege = Boolean.TRUE.equals(tenantJdbc.queryForObject(
                        "select current_user='nexa_business_traceability_worker' "
                                + "and current_setting('app.current_tenant_id', true)=? "
                                + "and current_setting('app.current_workspace_id', true)=? "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','tenant_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','workspace_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','recipient_membership_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','installation_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','platform','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','surface','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','status','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','created_at','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','updated_at','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_subscription','version','SELECT') "
                                + "and not has_column_privilege(current_user,'notifications.push_subscription','provider_token_hash','SELECT') "
                                + "and not has_table_privilege(current_user,'notifications.push_subscription','SELECT') "
                                + "and not has_table_privilege(current_user,'notifications.push_subscription','INSERT') "
                                + "and not has_table_privilege(current_user,'notifications.push_subscription','UPDATE') "
                                + "and not has_table_privilege(current_user,'notifications.push_subscription','DELETE') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','tenant_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','workspace_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','subscription_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','event_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','status','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','claim_token','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','lease_until','SELECT') "
                                + "and not has_column_privilege(current_user,'notifications.push_delivery_claim','delivery_key','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','tenant_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','workspace_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','subscription_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','event_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','delivery_key','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','status','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','claim_token','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','lease_until','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','created_at','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','updated_at','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','status','UPDATE') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','claim_token','UPDATE') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','lease_until','UPDATE') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_claim','updated_at','UPDATE') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_claim','SELECT') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_claim','INSERT') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_claim','UPDATE') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_claim','DELETE') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','tenant_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','workspace_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','subscription_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','event_id','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','status','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','attempt_number','SELECT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','tenant_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','workspace_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','subscription_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','event_id','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','event_type','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','status','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','provider_code','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','error','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','attempt_number','INSERT') "
                                + "and has_column_privilege(current_user,'notifications.push_delivery_attempt','created_at','INSERT') "
                                + "and not has_column_privilege(current_user,'notifications.push_delivery_attempt','error','SELECT') "
                                + "and not has_column_privilege(current_user,'notifications.push_delivery_attempt','status','UPDATE') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_attempt','SELECT') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_attempt','INSERT') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_attempt','UPDATE') "
                                + "and not has_table_privilege(current_user,'notifications.push_delivery_attempt','DELETE') "
                                + "and exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                                + "where n.nspname='notifications' and c.relname='push_subscription' "
                                + "and c.relrowsecurity and c.relforcerowsecurity) "
                                + "and exists (select 1 from pg_policies where schemaname='notifications' "
                                + "and tablename='push_subscription' and policyname='v17_push_subscription_scope' "
                                + "and qual like '%app.current_tenant_id%' and qual like '%app.current_workspace_id%' "
                                + "and with_check like '%app.current_tenant_id%' and with_check like '%app.current_workspace_id%') "
                                + "and exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                                + "where n.nspname='notifications' and c.relname='push_delivery_claim' "
                                + "and c.relrowsecurity and c.relforcerowsecurity) "
                                + "and exists (select 1 from pg_policies where schemaname='notifications' "
                                + "and tablename='push_delivery_claim' and policyname='v17_push_delivery_claim_scope' "
                                + "and qual like '%app.current_tenant_id%' and qual like '%app.current_workspace_id%' "
                                + "and with_check like '%app.current_tenant_id%' and with_check like '%app.current_workspace_id%') "
                                + "and exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                                + "where n.nspname='notifications' and c.relname='push_delivery_attempt' "
                                + "and c.relrowsecurity and c.relforcerowsecurity) "
                                + "and exists (select 1 from pg_policies where schemaname='notifications' "
                                + "and tablename='push_delivery_attempt' and policyname='v17_push_delivery_attempt_scope' "
                                + "and qual like '%app.current_tenant_id%' and qual like '%app.current_workspace_id%' "
                                + "and with_check like '%app.current_tenant_id%' and with_check like '%app.current_workspace_id%')",
                        Boolean.class, tenant.toString(), workspace.toString()));
                return new WorkerProbe(businessFacts, ownerEvents, lockedOwnerEventIds, otherWorkspaceEventIds,
                        minimalPrivilege, materialChangeExpiryPrivilege, notificationPushPrivilege);
            });

            assertThat(pendingRows.businessFacts())
                    .allSatisfy(event -> {
                        assertThat(event.tenantId()).isEqualTo(tenant);
                        assertThat(event.workspaceId()).isEqualTo(workspace);
                    })
                    .extracting(event -> event.eventId())
                    .doesNotContain(eventId);
            assertThat(pendingRows.ownerEvents())
                    .allSatisfy(event -> {
                        assertThat(event.tenantId()).isEqualTo(tenant);
                        assertThat(event.workspaceId()).isEqualTo(workspace);
                        assertThat(event.eventType()).isEqualTo(eventType);
                    })
                    .extracting(event -> event.eventId())
                    .contains(eventId);
            assertThat(pendingRows.lockedOwnerEventIds()).containsExactly(eventId);
            assertThat(pendingRows.otherWorkspaceEventIds()).isEmpty();
            assertThat(pendingRows.minimalPrivilege()).isTrue();
            assertThat(pendingRows.materialChangeExpiryPrivilege()).isTrue();
            assertThat(pendingRows.notificationPushPrivilege()).isTrue();

            assertThatThrownBy(() -> traceabilityWorkerRouter.inTransaction(tenant, workspace,
                    (tenantJdbc, ignored) -> tenantJdbc.update("update integration.outbox_event "
                            + "set event_id=event_id where event_id=? and tenant_id=? and workspace_id=?",
                            eventId, tenant, workspace)))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("Tenant outbox event identity is immutable"));
            assertThat(tenantTestJdbc.queryForObject("select event_id from integration.outbox_event "
                    + "where event_id=? and tenant_id=? and workspace_id=?", UUID.class, eventId, tenant, workspace))
                    .isEqualTo(eventId);
        } finally {
            tenantTestJdbc.update("delete from integration.outbox_event where event_id=? and tenant_id=? "
                    + "and workspace_id=?", eventId, tenant, workspace);
        }
    }

    @Test
    void tenantInboxReceiptsAreForcedAndScopedForTraceabilityWorker() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID otherWorkspace = UUID.randomUUID();
        UUID workspaceReceipt = UUID.randomUUID();
        UUID otherWorkspaceReceipt = UUID.randomUUID();
        UUID workerReceipt = UUID.randomUUID();
        UUID rejectedReceipt = UUID.randomUUID();
        String consumer = "tenant-inbox-rls-" + UUID.randomUUID();
        JdbcTemplate tenantTestJdbc = tenantJdbcFor(tenant);

        try {
            tenantTestJdbc.update("insert into nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) values (?,?)",
                    tenant, otherWorkspace);
            tenantTestJdbc.update("insert into integration.inbox_event "
                            + "(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                            + "values (?,?,?,?,current_timestamp,'PROCESSED')",
                    consumer, workspaceReceipt, tenant, workspace);
            tenantTestJdbc.update("insert into integration.inbox_event "
                            + "(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                            + "values (?,?,?,?,current_timestamp,'IGNORED')",
                    consumer, otherWorkspaceReceipt, tenant, otherWorkspace);

            traceabilityWorkerRouter.inTransaction(tenant, workspace, (tenantJdbc, ignored) -> {
                assertThat(tenantJdbc.queryForList("select event_id from integration.inbox_event "
                                + "where consumer_name=? and event_id in (?,?) order by event_id",
                        UUID.class, consumer, workspaceReceipt, otherWorkspaceReceipt))
                        .as("traceability reads see inbox receipts from the explicit Workspace only")
                        .containsExactly(workspaceReceipt);

                assertThat(tenantJdbc.queryForObject("select relation.relrowsecurity and relation.relforcerowsecurity "
                                + "from pg_class relation join pg_namespace ns on ns.oid=relation.relnamespace "
                                + "where ns.nspname='integration' and relation.relname='inbox_event'",
                        Boolean.class)).as("Tenant inbox has FORCE RLS").isTrue();
                assertThat(tenantJdbc.queryForList("select policyname from pg_policies "
                                + "where schemaname='integration' and tablename='inbox_event' order by policyname",
                        String.class))
                        .containsExactly("inbox_event_direct_scope_insert", "inbox_event_direct_scope_select");

                assertThat(tenantJdbc.queryForObject("select current_user='nexa_business_traceability_worker' "
                                + "and has_column_privilege(current_user,'integration.inbox_event','consumer_name','SELECT') "
                                + "and has_column_privilege(current_user,'integration.inbox_event','tenant_id','SELECT') "
                                + "and has_column_privilege(current_user,'integration.inbox_event','processed_at','INSERT') "
                                + "and not has_table_privilege(current_user,'integration.inbox_event','SELECT') "
                                + "and not has_table_privilege(current_user,'integration.inbox_event','INSERT') "
                                + "and not has_table_privilege(current_user,'integration.inbox_event','UPDATE') "
                                + "and not has_table_privilege(current_user,'integration.inbox_event','DELETE')",
                        Boolean.class)).as("worker retains its narrow receipt column grants").isTrue();
                assertThat(tenantJdbc.queryForObject("select not has_table_privilege('nexa_runtime',"
                                + "'integration.inbox_event','SELECT') "
                                + "and not has_table_privilege('nexa_runtime','integration.inbox_event','INSERT') "
                                + "and not has_table_privilege('nexa_runtime','integration.inbox_event','UPDATE') "
                                + "and not has_table_privilege('nexa_runtime','integration.inbox_event','DELETE') "
                                + "and not has_any_column_privilege('nexa_runtime','integration.inbox_event','SELECT') "
                                + "and not has_any_column_privilege('nexa_runtime','integration.inbox_event','INSERT') "
                                + "and not has_any_column_privilege('nexa_runtime','integration.inbox_event','UPDATE')",
                        Boolean.class)).as("the unused application runtime has no physical Tenant inbox grants").isTrue();

                assertThat(tenantJdbc.update("insert into integration.inbox_event "
                                + "(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                                + "values (?,?,?,?,current_timestamp,'PROCESSED')",
                        consumer, workerReceipt, tenant, workspace))
                        .as("the scoped traceability worker can append a receipt").isEqualTo(1);
                return null;
            });

            assertThatThrownBy(() -> traceabilityWorkerRouter.inTransaction(tenant, workspace,
                    (tenantJdbc, ignored) -> tenantJdbc.update("insert into integration.inbox_event "
                                    + "(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                                    + "values (?,?,?,?,current_timestamp,'PROCESSED')",
                            consumer, rejectedReceipt, tenant, otherWorkspace)))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("new row violates row-level security policy"));
        } finally {
            tenantTestJdbc.update("delete from integration.inbox_event where consumer_name=? and event_id in (?,?,?,?)",
                    consumer, workspaceReceipt, otherWorkspaceReceipt, workerReceipt, rejectedReceipt);
            tenantTestJdbc.update("delete from nexa_platform.tenant_workspace_scope_anchor where tenant_id=? and workspace_id=?",
                    tenant, otherWorkspace);
        }
    }

    /** Exercises the legacy worker consumer with a canonical event fixture; the HTTP approval action does not emit one. */
    @Test
    void approvedWorkerValidatesExpiryPolicyAndCommitsExpiredReleaseInItsScopedCallback() throws Exception {
        PurchaseRequestResource request = createApprovedPurchaseRequest();
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID requestId = UUID.fromString(request.id());
        Long snapshotRevision = tenantJdbc().queryForObject(
                "select snapshot_revision from nexa_platform.purchase_request_expiry_policy_snapshot "
                        + "where tenant_id=? and workspace_id=?", Long.class, tenant, workspace);
        assertThat(snapshotRevision).isNotNull();
        Integer expiryDays = tenantJdbc().queryForObject(
                "select expiry_days from nexa_platform.purchase_request_expiry_policy_snapshot "
                        + "where tenant_id=? and workspace_id=?", Integer.class, tenant, workspace);
        assertThat(expiryDays).isNotNull().isPositive();

        var adminJdbc = tenantJdbc();
        long requestVersion = adminJdbc.queryForObject("select version from sales.purchase_request "
                        + "where tenant_id=? and workspace_id=? and id=?", Long.class, tenant, workspace, requestId);
        assertThat(adminJdbc.queryForObject("select count(*) from integration.outbox_event "
                        + "where tenant_id=? and workspace_id=? and event_type='PURCHASE_REQUEST_APPROVED' "
                        + "and aggregate_id=?", Integer.class, tenant, workspace, requestId)).isZero();
        Instant approvedAt = Instant.now();
        UUID eventId = CanonicalOutbox.append(adminJdbc, "PURCHASE_REQUEST_APPROVED", "PurchaseRequest", requestId,
                tenant, workspace, approvedAt, "legacy-approval-fixture-" + requestId, null, "1.0",
                Map.of("purchaseRequestId", requestId.toString(), "purchaseRequestVersion", requestVersion));
        Instant expiresAt = approvedAt.plusMillis(100);
        Instant submittedAt = expiresAt.minus(Duration.ofDays(expiryDays));
        Instant createdAt = submittedAt.minusSeconds(1);
        new TransactionTemplate(new DataSourceTransactionManager(adminJdbc.getDataSource())).executeWithoutResult(status -> {
            adminJdbc.queryForObject("select set_config('app.expected_purchase_request_expiry_policy_revision', ?, true)",
                    String.class, snapshotRevision.toString());
            adminJdbc.update("update sales.purchase_request set created_at=?,submitted_at=?,reviewed_at=?,updated_at=?,expires_at=? "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    Timestamp.from(createdAt), Timestamp.from(submittedAt), Timestamp.from(approvedAt),
                    Timestamp.from(approvedAt), Timestamp.from(expiresAt), tenant, workspace, requestId);
        });

        TenantOutboxEvent event = adminJdbc.queryForObject("select event_id,event_type,aggregate_type,aggregate_id,"
                        + "tenant_id,workspace_id,occurred_at,correlation_id,causation_id,schema_version,"
                        + "payload::text,created_at from integration.outbox_event "
                        + "where event_id=? and tenant_id=? and workspace_id=?",
                (rs, row) -> new TenantOutboxEvent(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("correlation_id"),
                        rs.getObject("causation_id", UUID.class), rs.getString("schema_version"),
                        rs.getString("payload"), rs.getTimestamp("created_at").toInstant()),
                eventId, tenant, workspace);

        long waitMillis = Duration.between(Instant.now(), expiresAt.plusMillis(20)).toMillis();
        if (waitMillis > 0) Thread.sleep(waitMillis);

        var afterRead = traceabilityWorkerRouter.inTransaction(tenant, workspace, (workerJdbc, ignored) ->
                traceabilityBindings.bindWorker(workerJdbc).worker()
                        .inspectPendingEvent(event, approvedConsumer.consumerName(), approvedConsumer::readTenantSnapshot)
                        .orElseThrow());
        var prepared = afterRead.preflight();
        AtomicReference<String> installedRevision = new AtomicReference<>();
        boolean consumed = traceabilityWorkerRouter.inTransaction(tenant, workspace, (workerJdbc, transactionManager) ->
                traceabilityBindings.bindWorker(workerJdbc).worker().consumePendingEvent(event,
                        approvedConsumer.consumerName(), transactionManager, (callbackJdbc, callbackManager, current) -> {
                            prepared.consume(callbackJdbc, callbackManager, current);
                            installedRevision.set(callbackJdbc.queryForObject(
                                    "select current_setting('app.expected_purchase_request_expiry_policy_revision', true)",
                                    String.class));
                        }));

        assertThat(consumed).isTrue();
        assertThat(installedRevision.get()).isEqualTo(snapshotRevision.toString());
        assertThat(adminJdbc.queryForObject("select status from sales.purchase_request where tenant_id=? "
                + "and workspace_id=? and id=?", String.class, tenant, workspace, requestId)).isEqualTo("EXPIRED");
        UUID commitment = adminJdbc.queryForObject("select id from sales.commercial_commitment "
                + "where tenant_id=? and workspace_id=? and purchase_request_id=?", UUID.class, tenant, workspace, requestId);
        assertThat(adminJdbc.queryForObject("select status from sales.commercial_commitment where id=?",
                String.class, commitment)).isEqualTo("EXPIRED");
        assertThat(adminJdbc.queryForObject("select count(*) from warehouse.inventory_backing "
                + "where commercial_commitment_id=? and status='RELEASED'", Integer.class, commitment)).isPositive();

        WorkerPolicyProbe policyProbe = traceabilityWorkerRouter.inTransaction(tenant, workspace, (workerJdbc, ignored) ->
                new WorkerPolicyProbe(
                        workerJdbc.queryForObject("select snapshot_revision from nexa_platform.purchase_request_expiry_policy_snapshot "
                                + "where tenant_id=? and workspace_id=?", Long.class, tenant, workspace),
                        workerJdbc.queryForObject("select count(*) from nexa_platform.purchase_request_expiry_policy_snapshot "
                                + "where tenant_id=? and workspace_id=?", Integer.class, UUID.randomUUID(), workspace),
                        workerJdbc.queryForObject("select count(*) from nexa_platform.purchase_request_expiry_policy_snapshot "
                                + "where tenant_id=? and workspace_id=?", Integer.class, tenant, UUID.randomUUID()),
                        Boolean.TRUE.equals(workerJdbc.queryForObject("select not has_table_privilege(current_user, "
                                + "'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE') "
                                + "and not has_column_privilege(current_user, "
                                + "'nexa_platform.purchase_request_expiry_policy_snapshot', 'snapshot_revision', 'UPDATE')",
                                Boolean.class)),
                        Boolean.TRUE.equals(workerJdbc.queryForObject("select not has_table_privilege(current_user, "
                                + "'warehouse.inventory_backing', 'UPDATE') "
                                + "and has_column_privilege(current_user, 'warehouse.inventory_backing', 'status', 'UPDATE') "
                                + "and not has_column_privilege(current_user, 'warehouse.inventory_backing', 'commercial_commitment_id', 'UPDATE') "
                                + "and not has_column_privilege(current_user, 'warehouse.inventory_backing', 'requested_at', 'SELECT') "
                                + "and not has_table_privilege(current_user, 'warehouse.inventory_backing_line', 'SELECT') "
                                + "and not has_table_privilege(current_user, 'warehouse.inventory_backing_line', 'UPDATE') "
                                + "and not has_table_privilege(current_user, 'warehouse.inventory_backing_position', 'SELECT') "
                                + "and not has_table_privilege(current_user, 'warehouse.inventory_backing_position', 'UPDATE')",
                                Boolean.class))));
        assertThat(policyProbe.ownRevision()).isEqualTo(snapshotRevision);
        assertThat(policyProbe.otherTenantRows()).isZero();
        assertThat(policyProbe.otherWorkspaceRows()).isZero();
        assertThat(policyProbe.cannotUpdate()).isTrue();
        assertThat(policyProbe.onlyBackingReleaseAccess()).isTrue();
    }

    private record WorkerProbe(
            java.util.List<com.nexa.api.shared.events.TenantOutboxEvent> businessFacts,
            java.util.List<com.nexa.api.shared.events.TenantOutboxEvent> ownerEvents,
            java.util.List<UUID> lockedOwnerEventIds,
            java.util.List<UUID> otherWorkspaceEventIds,
            boolean minimalPrivilege,
            boolean materialChangeExpiryPrivilege,
            boolean notificationPushPrivilege) { }

    private record WorkerPolicyProbe(Long ownRevision, int otherTenantRows, int otherWorkspaceRows,
            boolean cannotUpdate, boolean onlyBackingReleaseAccess) { }

    private boolean scopeReaderHasColumnPrivilege(String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select has_column_privilege("
                + "'nexa_business_documents_scope_reader', 'tenant_management.workspace_membership', ?, 'SELECT')",
                Boolean.class, column));
    }
}
