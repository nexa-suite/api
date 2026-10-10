package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundBuyerWalletReadPort;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.ClientAccountPersistenceAdapter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.service.BuyerWalletReadService;
import com.nexa.api.payments.infrastructure.persistence.JdbcBuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
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
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Buyer wallet reads across two separate physical Tenant databases. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBoundBuyerWalletReadIT {
    private static final String MIGRATOR = "nexa_migrator";
    private static final String MIGRATOR_PASSWORD = "tenant-wallet-read-migrator-test-only";
    private static final String RUNTIME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "tenant-wallet-read-runtime-test-only";
    private static final String POLICY_WRITER = "nexa_policy_snapshot_writer";
    private static final String POLICY_WRITER_PASSWORD = "tenant-wallet-read-policy-test-only";

    @Container
    private static final PostgreSQLContainer TENANT_A = tenant("wallet-read-a");
    @Container
    private static final PostgreSQLContainer TENANT_B = tenant("wallet-read-b");

    private static TenantFixture fixtureA;
    private static TenantFixture fixtureB;
    private static TenantFixture fixtureBWithoutWallet;
    private static TenantBusinessDatabaseRouter router;
    private static BuyerWalletReadService walletRead;

    @BeforeAll
    static void prepareTenantDatabases() throws SQLException {
        fixtureA = prepare(TENANT_A, new BigDecimal("31.2500"));
        fixtureB = prepare(TENANT_B, new BigDecimal("74.5000"));
        fixtureBWithoutWallet = addBuyerWithoutWallet(TENANT_B, fixtureB);

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
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(connection.jdbcUrl());
            config.setUsername(connection.username());
            config.setPassword(connection.password());
            config.setMaximumPoolSize(2);
            config.setPoolName("wallet-read-" + binding.databaseIdentity());
            return new HikariDataSource(config);
        };
        TenantCustomerAccountQueryFactory relationships = tenantJdbc -> new ClientAccountPersistenceAdapter(tenantJdbc);
        router = new TenantBusinessDatabaseRouter(authority, dataSources, 2);
        walletRead = new BuyerWalletReadService(new TenantBoundBuyerWalletReadPort(router, relationships,
                new JdbcBuyerWalletTenantDatabaseAdapterFactory()));
    }

    @AfterAll
    static void closeTenantPools() {
        if (router != null) router.close();
    }

    @Test
    void readsBalancesAndLedgerFromEachBuyersOwnPhysicalTenantDatabase() {
        BuyerWalletModels.WalletView walletA = read(fixtureA);
        BuyerWalletModels.WalletView walletB = read(fixtureB);

        assertThat(walletA.status()).isEqualTo(BuyerWalletModels.WalletStatus.ACTIVE);
        assertThat(walletA.postedBalance()).isEqualByComparingTo("31.2500");
        assertThat(walletA.reservedBalance()).isEqualByComparingTo("0.0000");
        assertThat(walletA.availableBalance()).isEqualByComparingTo("31.2500");
        assertThat(walletA.movements().items()).singleElement().satisfies(movement -> {
            assertThat(movement.type()).isEqualTo("CREDIT");
            assertThat(movement.amountDelta()).isEqualByComparingTo("31.2500");
        });

        assertThat(walletB.postedBalance()).isEqualByComparingTo("74.5000");
        assertThat(walletB.availableBalance()).isEqualByComparingTo("74.5000");
        assertThat(walletA.postedBalance()).isNotEqualByComparingTo(walletB.postedBalance());
        assertThat(walletA.toString()).doesNotContain("provider", "source", "actor");
        assertThat(walletB.toString()).doesNotContain("provider", "source", "actor");
    }

    @Test
    void absentWalletIsExplicitAndRelationshipMustExistInThatTenantDatabase() {
        BuyerWalletModels.WalletView absent = read(fixtureBWithoutWallet);
        assertThat(absent.status()).isEqualTo(BuyerWalletModels.WalletStatus.NOT_INITIALIZED);
        assertThat(absent.postedBalance()).isNull();
        assertThat(absent.reservedBalance()).isNull();
        assertThat(absent.availableBalance()).isNull();

        CurrentAccessContext wrongBuyer = context(fixtureBWithoutWallet,
                UUID.randomUUID(), UUID.randomUUID());
        RlsRequestScope.set(fixtureBWithoutWallet.tenantId().value(), fixtureBWithoutWallet.workspaceId().value());
        try {
            assertThatThrownBy(() -> walletRead.getCurrentBuyerWallet(wrongBuyer, 0, 25))
                    .isInstanceOf(AccessPolicyViolation.class);
        } finally {
            RlsRequestScope.clear();
        }
    }

    private static BuyerWalletModels.WalletView read(TenantFixture fixture) {
        CurrentAccessContext context = context(fixture, fixture.buyerIdentityId(), fixture.membershipId());
        RlsRequestScope.set(fixture.tenantId().value(), fixture.workspaceId().value());
        try {
            return walletRead.getCurrentBuyerWallet(context, 0, 25);
        } finally {
            RlsRequestScope.clear();
        }
    }

    private static CurrentAccessContext context(TenantFixture fixture, UUID identity, UUID membershipId) {
        Membership membership = new Membership(new MembershipId(membershipId), new UserId(identity),
                fixture.tenantId(), fixture.workspaceId(), Set.of(MembershipRole.BUYER),
                MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), Surface.PORTAL);
    }

    private static TenantFixture prepare(PostgreSQLContainer database, BigDecimal balance) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID databaseIdentity = UUID.randomUUID();
        UUID buyerIdentityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        String secretReference = "test/tenant-wallet-read/" + database.getDatabaseName();

        provisionRoles(database);
        migrate(database, "2");
        seedScope(database, tenantId, workspaceId, databaseIdentity);
        migrate(database, "5");
        seedBuyerRelationship(database, tenantId, workspaceId, clientAccountId, membershipId);
        seedWallet(database, tenantId, workspaceId, buyerIdentityId, balance);
        return new TenantFixture(new TenantId(tenantId), new WorkspaceId(workspaceId), databaseIdentity,
                buyerIdentityId, membershipId, clientAccountId, secretReference);
    }

    private static TenantFixture addBuyerWithoutWallet(PostgreSQLContainer database, TenantFixture scope) {
        UUID buyerIdentityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        seedBuyerRelationship(database, scope.tenantId().value(), scope.workspaceId().value(), clientAccountId,
                membershipId);
        return new TenantFixture(scope.tenantId(), scope.workspaceId(), scope.databaseIdentity(), buyerIdentityId,
                membershipId, clientAccountId, scope.secretReference());
    }

    private static void provisionRoles(PostgreSQLContainer database) throws SQLException {
        try (Connection connection = adminConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + POLICY_WRITER + " LOGIN PASSWORD '" + POLICY_WRITER_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + database.getDatabaseName() + "\" TO " + MIGRATOR);
            statement.execute("GRANT CONNECT ON DATABASE \"" + database.getDatabaseName() + "\" TO " + RUNTIME
                    + ", " + POLICY_WRITER);
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

    private static void seedBuyerRelationship(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
            UUID accountId, UUID membershipId) {
        JdbcTemplate admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        Timestamp now = Timestamp.from(Instant.now());
        admin.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,"
                        + "tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                accountId, tenantId, workspaceId, "WALLET-" + accountId.toString().substring(0, 8), "Fixture buyer",
                "Fixture buyer", "PE", "RUC", "20" + accountId.toString().replace("-", "").substring(0, 10),
                "B2B", "Fixture contact", accountId + "@example.test", "+51999999999", "Fixture delivery",
                "PREPAID", now, now);
        admin.update("insert into sales.client_account_membership "
                        + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?)",
                accountId, membershipId, tenantId, workspaceId, now);
    }

    private static void seedWallet(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
            UUID buyerIdentityId, BigDecimal balance) {
        JdbcTemplate admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        UUID accountId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        admin.update("insert into payments.buyer_wallet_account "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,posted_balance) values (?,?,?,?,'PEN',?)",
                accountId, tenantId, workspaceId, buyerIdentityId, balance);
        // Test fixture represents a provider-confirmed recharge; this creates no public payment flow.
        admin.update("insert into payments.buyer_wallet_ledger_entry "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,entry_type,amount_delta,source_id,"
                        + "idempotency_key,provider_code,provider_event_id,occurred_at) "
                        + "values (?,?,?,?,'PEN','PROVIDER_RECHARGE',?,?,?,'TEST_PROVIDER',?,?)",
                UUID.randomUUID(), tenantId, workspaceId, buyerIdentityId, balance, sourceId,
                "confirmed-wallet-fixture-" + sourceId, sourceId.toString(), Timestamp.from(Instant.now()));
    }

    private static TenantBusinessDatabaseCredentials credentials(PostgreSQLContainer database) {
        return new TenantBusinessDatabaseCredentials(database.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private static Connection adminConnection(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static PostgreSQLContainer tenant(String name) {
        return new PostgreSQLContainer("postgres:18.4-alpine").withDatabaseName(name)
                .withUsername("postgres").withPassword("wallet-read-admin-test-only");
    }

    private record TenantFixture(TenantId tenantId, WorkspaceId workspaceId, UUID databaseIdentity,
            UUID buyerIdentityId, UUID membershipId, UUID clientAccountId, String secretReference) { }
}
