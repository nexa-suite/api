package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import com.nexa.api.bootstrap.runtime.boundaries.TenantNotificationPushWorkerScheduler;
import com.nexa.api.bootstrap.runtime.events.TenantBusinessNotificationProjectionConsumer;
import com.nexa.api.bootstrap.runtime.events.TenantNotificationPushDeliveryConsumer;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.notifications.infrastructure.FcmPushConfiguration;
import com.nexa.api.notifications.infrastructure.PushDestinationCipher;
import com.nexa.api.notifications.tenantdatabase.TenantPushProviderFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class CloudTenantNotificationPushCompositionIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

    @BeforeAll static void createScopeReader() throws Exception {
        try (var connection = POSTGRES.createConnection(""); var sql = connection.createStatement()) {
            sql.execute("create role nexa_business_documents_scope_reader login password 'test-only-scope-reader' "
                    + "noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
            sql.execute("create schema tenant_management");
            sql.execute("create table tenant_management.workspace(id uuid primary key,tenant_id uuid,status text)");
            sql.execute("create table tenant_management.tenant_business_database_binding(tenant_id uuid primary key,lifecycle_state text)");
            sql.execute("create table tenant_management.tenant_business_database_workspace_anchor_task(tenant_id uuid,workspace_id uuid,status text)");
            sql.execute("grant usage on schema tenant_management to nexa_business_documents_scope_reader");
            sql.execute("grant select on all tables in schema tenant_management to nexa_business_documents_scope_reader");
            sql.execute("alter table tenant_management.workspace enable row level security");
            sql.execute("alter table tenant_management.workspace force row level security");
            sql.execute("create policy ready_scan on tenant_management.workspace for select using (current_setting('app.cross_scope_workspace_scan',true)='true')");
            for (int tenant : new int[] {1,2}) {
                UUID tenantId = new UUID(0,tenant);
                sql.execute("insert into tenant_management.tenant_business_database_binding values ('"+tenantId+"','READY')");
                for (int workspace = 1; workspace <= 2; workspace++) {
                    UUID workspaceId = new UUID(tenant,workspace);
                    sql.execute("insert into tenant_management.workspace values ('"+workspaceId+"','"+tenantId+"','ACTIVE')");
                    sql.execute("insert into tenant_management.tenant_business_database_workspace_anchor_task values ('"+tenantId+"','"+workspaceId+"','READY')");
                }
            }

        }
    }

    @Test void singleBindingScopeReaderFiltersOtherReadyTenantsBeforePaging() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername("nexa_business_documents_scope_reader");
        config.setPassword("test-only-scope-reader");
        config.setMaximumPoolSize(1);
        UUID tenant = new UUID(0,1);
        try (var reader = new JdbcTenantBusinessDocumentWorkerScopeQuery(new HikariDataSource(config), tenant)) {
            var first = reader.listReadyWorkspaces(null,null,1);
            assertThat(first).containsExactly(new TenantBusinessDocumentWorkerScopeQuery.Scope(tenant,new UUID(1,1)));
            assertThat(reader.listReadyWorkspaces(tenant,first.getFirst().workspaceId(),100))
                    .containsExactly(new TenantBusinessDocumentWorkerScopeQuery.Scope(tenant,new UUID(1,2)));
            assertThat(reader.listReadyWorkspaces(tenant,new UUID(1,2),100)).isEmpty();
            assertThat(reader.isReadyWorkspace(tenant,new UUID(1,1))).isTrue();
            assertThat(reader.isReadyWorkspace(new UUID(0,2),new UUID(2,1))).isFalse();
            assertThatThrownBy(() -> reader.listReadyWorkspaces(new UUID(0,2),new UUID(2,1),1))
                    .hasMessageContaining("escaped the configured Tenant");
        }
    }

    @Test void migrationRejectsAWorkerWithExistingTableAccessOrUnsafeRoleFlags() throws Exception {
        String migration;
        try (var source = getClass().getClassLoader().getResourceAsStream(
                "db/tenant-migration/V46__grant_notification_push_worker.sql")) {
            migration = new String(java.util.Objects.requireNonNull(source).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String precondition = migration.substring(0, migration.indexOf("END $$;") + "END $$;".length());
        try (var connection = POSTGRES.createConnection(""); var sql = connection.createStatement()) {
            sql.execute("create role nexa_notification_push_worker nologin noinherit");
            sql.execute("create schema integration");
            sql.execute("create table integration.outbox_event(id integer)");
            sql.execute("create schema sales");
            sql.execute("create table sales.private_fixture(id integer)");
            sql.execute("grant select on sales.private_fixture to nexa_notification_push_worker");
            assertThatThrownBy(() -> sql.execute(precondition)).hasMessageContaining("without pre-existing table grants");
            sql.execute("revoke select on sales.private_fixture from nexa_notification_push_worker");
            sql.execute("alter role nexa_notification_push_worker inherit");
            assertThatThrownBy(() -> sql.execute(precondition)).hasMessageContaining("isolated notification worker role");
        }
    }

    @Test void startsOnlyTheExplicitNotificationWorkerWithTraceabilityDisabled() {
        var projection = mock(TenantBusinessNotificationProjectionConsumer.class);
        var delivery = mock(TenantNotificationPushDeliveryConsumer.class);
        when(projection.consumerName()).thenReturn("notifications-business-projection-v1");
        when(delivery.consumerName()).thenReturn("notifications-push-delivery-v1");
        new ApplicationContextRunner()
                .withUserConfiguration(CloudTenantBusinessDatabaseRouterConfiguration.class,
                        CloudTenantNotificationPushWorkerConfiguration.class, FcmPushConfiguration.class,
                        TenantNotificationPushWorkerScheduler.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ResolveCurrentAccessContextUseCase.class, () -> mock(ResolveCurrentAccessContextUseCase.class))
                .withBean(TenantBusinessTraceabilityBindingsFactory.class, () -> mock(TenantBusinessTraceabilityBindingsFactory.class))
                .withBean(TenantBusinessNotificationProjectionConsumer.class, () -> projection)
                .withBean(TenantNotificationPushDeliveryConsumer.class, () -> delivery)
                .withBean(PushDestinationCipher.class, () -> mock(PushDestinationCipher.class))
                .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class))
                .withPropertyValues("spring.profiles.active=tenant-cloud", "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD=test-only-scope-reader",
                        "nexa.database.require-least-privilege-runtime=true",
                        "nexa.tenant-business.cloud-router.enabled=true", "nexa.tenant-business.notifications.enabled=true",
                        "nexa.tenant-business.cloud-notification-push.enabled=true", "nexa.notifications.push.provider=fcm",
                        "nexa.notifications.push.fcm.project-id=nexa-test-project",
                        "nexa.tenant-business.cloud-router.credentials-source=environment",
                        "nexa.tenant-business.cloud-router.environment.credential-secret-reference=cloud-tenant-db:0123456789abcdef0123456789abcdef",
                        "nexa.tenant-business.cloud-router.environment.tenant-id=032b470c-157f-347c-b1df-d00e62788fb2",
                        "nexa.tenant-business.cloud-router.environment.database-identity=714cbaee-6090-405c-a87c-497a2ebfa640",
                        "nexa.tenant-business.cloud-router.environment.jdbc-url=jdbc:postgresql://ep-example-123.c-7.us-east-2.aws.neon.tech:5432/nexa_tenant_business?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require",
                        "nexa.tenant-business.cloud-router.environment.runtime-username=nexa_runtime",
                        "nexa.tenant-business.cloud-router.environment.runtime-password=runtime-test-secret",
                        "nexa.tenant-business.cloud-router.environment.policy-writer-username=nexa_policy_snapshot_writer",
                        "nexa.tenant-business.cloud-router.environment.policy-writer-password=writer-test-secret",
                        "nexa.tenant-business.cloud-router.environment.notification-worker-password=worker-test-secret")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TenantNotificationPushWorkerScheduler.class)
                        .hasSingleBean(TenantBusinessTraceabilityWorkerRouter.class).hasSingleBean(TenantPushProviderFactory.class));
    }
}
