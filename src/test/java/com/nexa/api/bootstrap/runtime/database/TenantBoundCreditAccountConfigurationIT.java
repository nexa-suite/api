package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundCreditAccountConfigurationUseCase;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businesstraceability.infrastructure.persistence.JdbcTenantBusinessTraceabilityCommandsFactory;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationUseCase;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.service.CreditAccountConfigurationApplicationService;
import com.nexa.api.creditreceivables.infrastructure.persistence.JdbcTenantCreditAccountAdapterFactory;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantCustomerAccountDirectoryQueryFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantLegacyCustomerCreditInitializationQueryFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.assertj.core.api.ThrowableAssert;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs BC-07 configuration commands against isolated physical Tenant databases. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBoundCreditAccountConfigurationIT {
    private static final String MIGRATOR = "nexa_credit_config_migrator";
    private static final String MIGRATOR_PASSWORD = "credit-config-migrator-test-only";
    private static final String RUNTIME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "credit-config-runtime-test-only";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Container
    private static final PostgreSQLContainer TENANT_A = tenant("credit-config-a");
    @Container
    private static final PostgreSQLContainer TENANT_B = tenant("credit-config-b");

    private static TenantFixture fixtureA;
    private static TenantFixture fixtureB;
    private static TenantBusinessDatabaseRouter router;
    private static CreditAccountConfigurationApplicationService configuration;
    private static TenantCreditAccountAdapterFactory creditAdapters;
    private static TenantBusinessTraceabilityBindingsFactory traceability;
    private static JdbcTenantCustomerAccountQueryFactory customerAccounts;

    @BeforeAll
    static void prepareTenantDatabases() throws SQLException {
        fixtureA = prepare(TENANT_A, "A");
        fixtureB = prepare(TENANT_B, "B");

        Map<String, TenantBusinessDatabaseCredentials> credentials = Map.of(
                fixtureA.secretReference(), credentials(TENANT_A),
                fixtureB.secretReference(), credentials(TENANT_B));
        TenantBusinessDatabaseAuthority authority = presented -> {
            TenantFixture fixture = fixtureA.tenantId().equals(presented.tenantId()) ? fixtureA
                    : fixtureB.tenantId().equals(presented.tenantId()) ? fixtureB : null;
            if (fixture == null) throw new AccessDeniedException("Unknown Tenant fixture");
            return new TenantBusinessDatabaseBinding(fixture.tenantId(), fixture.databaseIdentity(),
                    fixture.secretReference());
        };
        TenantBusinessDatabaseDataSourceFactory dataSources = binding -> {
            TenantBusinessDatabaseCredentials connection = credentials.get(binding.credentialSecretReference());
            if (connection == null) throw new IllegalStateException("No Tenant fixture credentials");
            HikariConfig pool = new HikariConfig();
            pool.setJdbcUrl(connection.jdbcUrl());
            pool.setUsername(connection.username());
            pool.setPassword(connection.password());
            pool.setMaximumPoolSize(3);
            pool.setPoolName("credit-config-" + binding.databaseIdentity());
            return new HikariDataSource(pool);
        };
        router = new TenantBusinessDatabaseRouter(authority, dataSources, 2);
        ObjectMapper mapper = new ObjectMapper();
        traceability = new TenantBusinessTraceabilityBindingsFactory(
                new JdbcTenantBusinessTraceabilityCommandsFactory(mapper),
                (tenantJdbc, tenantCommands) -> (tenant, workspace, limit) -> 0);
        creditAdapters = new JdbcTenantCreditAccountAdapterFactory(
                new JdbcTenantLegacyCustomerCreditInitializationQueryFactory(), mapper);
        customerAccounts = new JdbcTenantCustomerAccountQueryFactory();
        CreditAccountConfigurationUseCase useCase = new TenantBoundCreditAccountConfigurationUseCase(
                router, new JdbcTenantCustomerAccountDirectoryQueryFactory(), creditAdapters, traceability);
        configuration = new CreditAccountConfigurationApplicationService(useCase,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    static void closeTenantPools() {
        if (router != null) router.close();
    }

    @Test
    void createsReplaysUsesCasAndTotalDebtFloorThenSuspendsWithoutDeletingReservations() {
        CurrentAccessContext bom = context(fixtureA, MembershipRole.BUSINESS_OPERATIONS_MANAGER,
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE.code()));
        CurrentAccessContext owner = context(fixtureA, MembershipRole.COMPANY_OWNER,
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE.code()));
        var accountId = fixtureA.primaryAccountId();

        var absent = inScope(bom, () -> configuration.read(bom, accountId, "PEN"));
        assertThat(absent.status()).isEqualTo(CreditAccountConfigurationQuery.Status.NOT_CONFIGURED);
        assertThat(absent.creditLimit()).isNull();
        assertThat(absent.version()).isNull();

        var created = inScope(owner, () -> configuration.configure(owner, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("500"), true),
                null, "*", "first-config"));
        assertThat(created.status()).isEqualTo(CreditAccountConfigurationQuery.Status.ACTIVE);
        assertThat(created.version()).isZero();
        int tracesAfterCreate = countTraceRowsForAccount(TENANT_A, fixtureA, accountId);
        int outboxAfterCreate = countOutboxRowsForAccount(TENANT_A, fixtureA, accountId);
        int keysAfterCreate = countConfigurationKeysForAccount(TENANT_A, fixtureA, accountId);
        var replay = inScope(owner, () -> configuration.configure(owner, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("500"), true),
                null, "*", "first-config"));
        assertThat(replay).isEqualTo(created);
        assertThat(countTraceRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(tracesAfterCreate);
        assertThat(countOutboxRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(outboxAfterCreate);
        assertThat(countConfigurationKeysForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(keysAfterCreate);
        assertFailureCode(() -> inScope(owner, () -> configuration.configure(owner, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("501"), true),
                null, "*", "first-config")), "IDEMPOTENCY_PAYLOAD_CONFLICT");

        seedExposureAndObligations(TENANT_A, fixtureA, accountId);
        var withDebt = inScope(bom, () -> configuration.read(bom, accountId, "PEN"));
        assertThat(withDebt.financedExposure()).isEqualByComparingTo("100.0000");
        assertThat(withDebt.outstandingReceivables()).isEqualByComparingTo("200.0000");
        assertThat(withDebt.reservedExposure()).isEqualByComparingTo("50.0000");
        assertThat(withDebt.used()).isEqualByComparingTo("350.0000");

        assertFailureCode(() -> inScope(bom, () -> configuration.configure(bom, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("349"), true),
                "\"0\"", null, "below-floor")), "CREDIT_LIMIT_BELOW_USED");
        assertThat(inScope(bom, () -> configuration.read(bom, accountId, "PEN"))).isEqualTo(withDebt);

        var suspended = inScope(bom, () -> configuration.configure(bom, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("350"), false),
                "\"0\"", null, "suspend-at-floor"));
        assertThat(suspended.status()).isEqualTo(CreditAccountConfigurationQuery.Status.SUSPENDED);
        assertThat(suspended.version()).isEqualTo(1L);
        assertThat(suspended.used()).isEqualByComparingTo("350.0000");
        assertThat(reservationStatus(TENANT_A, fixtureA, accountId)).isEqualTo("RESERVED");
        assertThatThrownBy(() -> reserveNewCredit(bom, accountId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Client credit account is not configured");
        assertThat(reservationCount(TENANT_A, fixtureA, accountId)).isEqualTo(1);

        assertFailureCode(() -> inScope(bom, () -> configuration.configure(bom, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("400"), true),
                "\"0\"", null, "stale-version")), "CONCURRENCY_CONFLICT");
        assertThat(inScope(owner, () -> configuration.configure(owner, accountId,
                new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("500"), true),
                null, "*", "first-config"))).isEqualTo(created);

        CurrentAccessContext salesWithDedicatedPermission = context(fixtureA, MembershipRole.SALES,
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE.code()));
        CurrentAccessContext bomWithOnlyAdjustmentPermission = context(fixtureA,
                MembershipRole.BUSINESS_OPERATIONS_MANAGER, Set.of(PermissionKey.CLIENT_CREDIT_MANAGE.code()));
        assertThatThrownBy(() -> inScope(salesWithDedicatedPermission,
                () -> configuration.read(salesWithDedicatedPermission, accountId, "PEN")))
                .isInstanceOf(AccessPolicyViolation.class);
        assertThatThrownBy(() -> inScope(bomWithOnlyAdjustmentPermission,
                () -> configuration.read(bomWithOnlyAdjustmentPermission, accountId, "PEN")))
                .isInstanceOf(AccessPolicyViolation.class);

        CurrentAccessContext tenantB = context(fixtureB, MembershipRole.BUSINESS_OPERATIONS_MANAGER,
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE.code()));
        assertFailureCode(() -> inScope(tenantB,
                () -> configuration.read(tenantB, accountId, "PEN")), "CLIENT_ACCOUNT_NOT_FOUND");
        assertThat(inScope(tenantB, () -> configuration.read(tenantB, fixtureB.primaryAccountId(), "PEN")).status())
                .isEqualTo(CreditAccountConfigurationQuery.Status.NOT_CONFIGURED);

        assertThat(countTraceRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(2);
        assertThat(countOutboxRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(2);
        assertThat(countConfigurationKeysForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(2);
        assertThat(countTraceRows(TENANT_B, fixtureB)).isZero();
        assertThat(countOutboxRows(TENANT_B, fixtureB)).isZero();
    }

    @Test
    void concurrentFirstConfigurationCreatesOneAccountAndOneAtomicTrace() throws Exception {
        CurrentAccessContext bom = context(fixtureA, MembershipRole.BUSINESS_OPERATIONS_MANAGER,
                Set.of(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE.code()));
        UUID accountId = fixtureA.concurrentAccountId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentCreate(ready, start, bom, accountId, "race-first"));
            var second = executor.submit(() -> concurrentCreate(ready, start, bom, accountId, "race-second"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(outcome -> outcome.snapshot() != null)).hasSize(1);
            assertThat(outcomes.stream().filter(outcome -> outcome.failureCode() != null)
                    .map(Attempt::failureCode)).containsExactly("PRECONDITION_FAILED");
            assertThat(countAccountRows(TENANT_A, fixtureA, accountId)).isEqualTo(1);
            assertThat(countConfigurationKeys(TENANT_A, fixtureA, "race-%")).isEqualTo(1);
            assertThat(countTraceRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(1);
            assertThat(countOutboxRowsForAccount(TENANT_A, fixtureA, accountId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Attempt concurrentCreate(CountDownLatch ready, CountDownLatch start,
            CurrentAccessContext context, UUID accountId, String key) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Create race did not start");
        try {
            var snapshot = inScope(context, () -> configuration.configure(context, accountId,
                    new CreditAccountConfigurationApplicationService.ConfigurationRequest("PEN", amount("100"), true),
                    null, "*", key));
            return new Attempt(snapshot, null);
        } catch (CreditReceivableOperationException error) {
            return new Attempt(null, error.code());
        }
    }

    private static void reserveNewCredit(CurrentAccessContext context, UUID accountId) {
        inScope(context, () -> router.inTransaction(context, jdbc -> {
            var trace = traceability.bindTo(jdbc);
            TenantCreditAccountAdapterFactory.Bindings bindings = creditAdapters.bindTo(jdbc,
                    customerAccounts.bindTo(jdbc), creditAdapters.bindReceivablePaymentAccessTo(jdbc),
                    trace.commands(), trace.canonicalOutbox(), unusedAdjustmentSource());
            bindings.creditReservations().reserveForCommitment(context.tenantId().value(),
                    context.workspaceId().value(), accountId, null, UUID.randomUUID(), null,
                    amount("1"), "PEN", NOW);
            return null;
        }));
    }

    private static FinancialAdjustmentSource unusedAdjustmentSource() {
        return new FinancialAdjustmentSource() {
            @Override public Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                throw new AssertionError("Adjustment source is unused in this test");
            }
            @Override public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                throw new AssertionError("Adjustment source is unused in this test");
            }
        };
    }

    private static <T> T inScope(CurrentAccessContext context, java.util.function.Supplier<T> action) {
        RlsRequestScope.set(context.tenantId().value(), context.workspaceId().value());
        try {
            return action.get();
        } finally {
            RlsRequestScope.clear();
        }
    }

    private static void assertFailureCode(ThrowableAssert.ThrowingCallable command, String expectedCode) {
        assertThatThrownBy(command).isInstanceOfSatisfying(CreditReceivableOperationException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private static CurrentAccessContext context(TenantFixture fixture, MembershipRole role,
            Set<String> permissions) {
        UUID membershipId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        long authorizationVersion = 1L;
        Membership membership = new Membership(new MembershipId(membershipId), new UserId(userId),
                fixture.tenantId(), fixture.workspaceId(), Set.of(role), MembershipStatus.ACTIVE,
                authorizationVersion);
        EffectiveAuthorization authorization = new EffectiveAuthorization(
                Set.of(RoleDefinitionId.system(role.name()).toString()), Set.of(role.name()), permissions,
                authorizationVersion);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), Surface.PLATFORM);
    }

    private static TenantFixture prepare(PostgreSQLContainer database, String suffix) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID databaseIdentity = UUID.randomUUID();
        UUID primaryAccountId = UUID.randomUUID();
        UUID concurrentAccountId = UUID.randomUUID();
        String secretReference = "test/credit-config/" + database.getDatabaseName();

        provisionRoles(database);
        migrate(database, "2");
        seedScope(database, tenantId, workspaceId, databaseIdentity);
        migrate(database, "3");
        seedCustomerAccount(database, tenantId, workspaceId, primaryAccountId, "CONFIG-" + suffix + "-PRIMARY");
        seedCustomerAccount(database, tenantId, workspaceId, concurrentAccountId, "CONFIG-" + suffix + "-RACE");
        return new TenantFixture(new TenantId(tenantId), new WorkspaceId(workspaceId), databaseIdentity,
                primaryAccountId, concurrentAccountId, secretReference);
    }

    private static void provisionRoles(PostgreSQLContainer database) throws SQLException {
        try (Connection connection = adminConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + database.getDatabaseName() + "\" TO " + MIGRATOR);
            statement.execute("GRANT CONNECT ON DATABASE \"" + database.getDatabaseName() + "\" TO " + RUNTIME);
            statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR);
        }
    }

    private static void migrate(PostgreSQLContainer database, String target) {
        Path location = Path.of("src/main/resources/db/tenant-migration").toAbsolutePath().normalize();
        Flyway.configure().dataSource(database.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                .locations("filesystem:" + location).target(target).load().migrate();
    }

    private static void seedScope(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
            UUID databaseIdentity) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
                PreparedStatement identity = connection.prepareStatement("insert into nexa_platform.tenant_business_database_identity "
                        + "(singleton,tenant_id,database_identity) values (true,?,?)");
                PreparedStatement anchor = connection.prepareStatement("insert into nexa_platform.tenant_workspace_scope_anchor "
                        + "(tenant_id,workspace_id) values (?,?)")) {
            identity.setObject(1, tenantId);
            identity.setObject(2, databaseIdentity);
            identity.executeUpdate();
            anchor.setObject(1, tenantId);
            anchor.setObject(2, workspaceId);
            anchor.executeUpdate();
        }
    }

    private static void seedCustomerAccount(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
            UUID accountId, String code) {
        JdbcTemplate admin = adminJdbc(database);
        Timestamp now = Timestamp.from(NOW);
        admin.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,"
                        + "tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                accountId, tenantId, workspaceId, code, "Fixture Account", "Fixture Account", "PE", "RUC",
                "20" + accountId.toString().replace("-", "").substring(0, 10), "B2B", "Fixture", "fixture@example.test",
                "+51999999999", "Fixture delivery", "CREDIT", now, now);
    }

    private static void seedExposureAndObligations(PostgreSQLContainer database, TenantFixture fixture,
            UUID clientAccountId) {
        JdbcTemplate admin = adminJdbc(database);
        UUID creditAccountId = admin.queryForObject("select id from payments.credit_account where tenant_id=? "
                + "and workspace_id=? and client_account_id=? and currency='PEN'", UUID.class,
                fixture.tenantId().value(), fixture.workspaceId().value(), clientAccountId);
        admin.update("update payments.credit_account set credit_exposure=100,reserved_exposure=50 "
                        + "where tenant_id=? and workspace_id=? and id=?",
                fixture.tenantId().value(), fixture.workspaceId().value(), creditAccountId);
        UUID receivableId = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        admin.update("insert into payments.receivable (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,"
                        + "receivable_number,currency,amount,amount_paid,due_at,status,version,created_at,updated_at,adjustment_total) "
                        + "values (?,?,?,?,? ,? ,?,'PEN',200,0,?,'OPEN',0,?,?,0)",
                receivableId, fixture.tenantId().value(), fixture.workspaceId().value(), clientAccountId,
                "SALES_ORDER", UUID.randomUUID(), "CREDIT-" + receivableId, Timestamp.from(NOW.plusSeconds(86400)),
                now, now);
        admin.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,amount,status,"
                        + "idempotency_key,created_at) values (?,?,?, ?,50,'RESERVED',?,?)",
                UUID.randomUUID(), fixture.tenantId().value(), fixture.workspaceId().value(), creditAccountId,
                "fixture-reservation-" + UUID.randomUUID(), now);
    }

    private static int countTraceRows(PostgreSQLContainer database, TenantFixture fixture) {
        return adminJdbc(database).queryForObject("select count(*) from audit.event where tenant_id=? and workspace_id=? "
                        + "and event_type='CREDIT_ACCOUNT_CONFIGURATION_CHANGED'",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value());
    }

    private static int countOutboxRows(PostgreSQLContainer database, TenantFixture fixture) {
        return adminJdbc(database).queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and event_type='BusinessFactTraced.v1'",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value());
    }

    private static int countConfigurationKeys(PostgreSQLContainer database, TenantFixture fixture, String keyPattern) {
        return adminJdbc(database).queryForObject("select count(*) from sales.idempotency_record where tenant_id=? "
                        + "and workspace_id=? and operation='credit-account-configuration' and idempotency_key like ?",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value(), keyPattern);
    }

    private static int countAccountRows(PostgreSQLContainer database, TenantFixture fixture, UUID accountId) {
        return adminJdbc(database).queryForObject("select count(*) from payments.credit_account where tenant_id=? "
                        + "and workspace_id=? and client_account_id=?", Integer.class,
                fixture.tenantId().value(), fixture.workspaceId().value(), accountId);
    }

    private static String reservationStatus(PostgreSQLContainer database, TenantFixture fixture, UUID accountId) {
        return adminJdbc(database).queryForObject("select reservation.status from payments.credit_reservation reservation "
                        + "join payments.credit_account account on account.tenant_id=reservation.tenant_id "
                        + "and account.workspace_id=reservation.workspace_id and account.id=reservation.credit_account_id "
                        + "where account.tenant_id=? and account.workspace_id=? and account.client_account_id=?",
                String.class, fixture.tenantId().value(), fixture.workspaceId().value(), accountId);
    }

    private static int reservationCount(PostgreSQLContainer database, TenantFixture fixture, UUID accountId) {
        return adminJdbc(database).queryForObject("select count(*) from payments.credit_reservation reservation "
                        + "join payments.credit_account account on account.tenant_id=reservation.tenant_id "
                        + "and account.workspace_id=reservation.workspace_id and account.id=reservation.credit_account_id "
                        + "where account.tenant_id=? and account.workspace_id=? and account.client_account_id=?",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value(), accountId);
    }

    private static int countTraceRowsForAccount(PostgreSQLContainer database, TenantFixture fixture, UUID accountId) {
        return adminJdbc(database).queryForObject("select count(*) from audit.event where tenant_id=? and workspace_id=? "
                        + "and event_type='CREDIT_ACCOUNT_CONFIGURATION_CHANGED' and safe_metadata->>'clientAccountId'=?",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value(), accountId.toString());
    }

    private static int countConfigurationKeysForAccount(PostgreSQLContainer database, TenantFixture fixture,
            UUID accountId) {
        return adminJdbc(database).queryForObject("select count(*) from sales.idempotency_record record "
                        + "join payments.credit_account account on account.tenant_id=record.tenant_id "
                        + "and account.workspace_id=record.workspace_id and account.id=record.resource_id "
                        + "where record.tenant_id=? and record.workspace_id=? and account.client_account_id=? "
                        + "and record.operation='credit-account-configuration'",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value(), accountId);
    }

    private static int countOutboxRowsForAccount(PostgreSQLContainer database, TenantFixture fixture, UUID accountId) {
        return adminJdbc(database).queryForObject("select count(*) from integration.outbox_event where tenant_id=? "
                        + "and workspace_id=? and event_type='BusinessFactTraced.v1' and payload->'metadata'->>'clientAccountId'=?",
                Integer.class, fixture.tenantId().value(), fixture.workspaceId().value(), accountId.toString());
    }

    private static JdbcTemplate adminJdbc(PostgreSQLContainer database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    private static TenantBusinessDatabaseCredentials credentials(PostgreSQLContainer database) {
        return new TenantBusinessDatabaseCredentials(database.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private static Connection adminConnection(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static PostgreSQLContainer tenant(String name) {
        return new PostgreSQLContainer("postgres:18.4-alpine").withDatabaseName(name)
                .withUsername("postgres").withPassword("credit-config-admin-test-only");
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(4);
    }

    private record Attempt(CreditAccountConfigurationQuery.Snapshot snapshot, String failureCode) { }
    private record TenantFixture(TenantId tenantId, WorkspaceId workspaceId, UUID databaseIdentity,
            UUID primaryAccountId, UUID concurrentAccountId, String secretReference) { }
}
