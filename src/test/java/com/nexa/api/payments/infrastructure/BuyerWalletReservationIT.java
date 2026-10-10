package com.nexa.api.payments.infrastructure;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundBuyerWalletReadPort;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.bootstrap.runtime.boundaries.TenantSalesCommitmentCompositionProviderAdapter;
import com.nexa.api.businesstraceability.infrastructure.persistence.JdbcTenantBusinessTraceabilityCommandsFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcTenantSellableSkuQueryFactory;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcTenantCatalogItemSnapshotQueryFactory;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.ClientAccountPersistenceAdapter;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantCustomerAddressQueryFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.JdbcTenantLegacyCustomerCreditInitializationQueryFactory;
import com.nexa.api.edge.streaming.infrastructure.JdbcTenantChangeEventPersistenceFactory;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.inventoryavailability.infrastructure.persistence.JdbcTenantInventoryBackingCommandsFactory;
import com.nexa.api.inventoryavailability.infrastructure.persistence.JdbcTenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.inventoryavailability.infrastructure.persistence.JdbcTenantWarehouseSelectionQueryFactory;
import com.nexa.api.creditreceivables.infrastructure.persistence.JdbcTenantCreditAccountAdapterFactory;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.service.BuyerWalletReadService;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.RefundCommand;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.ReserveCommand;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.Reservation;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.ReservationStatus;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.TransitionCommand;
import com.nexa.api.payments.infrastructure.persistence.JdbcBuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.payments.infrastructure.persistence.JdbcTenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.infrastructure.persistence.JdbcTenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.salescommitment.infrastructure.seed.JdbcTenantCatalogItemSnapshotLookupFactory;
import com.nexa.api.salescommitment.infrastructure.TenantSalesCommitmentCompositionFactoryAdapter;
import com.nexa.api.salescommitment.infrastructure.commitment.JdbcTenantCommercialCommitmentFactory;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.salescommitment.application.publicapi.MapRoutingPort;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess.PurchaseRequestExpiryPolicySource;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess.SourceState;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tenant-local reservation lifecycle checks; no global wallet writer is registered. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerWalletReservationIT extends PaymentIntegrationSupport {
    private static final String MIGRATOR = "nexa_migrator";
    private static final String MIGRATOR_PASSWORD = "tenant-wallet-reservation-migrator-test-only";
    private static final String RUNTIME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "tenant-wallet-reservation-runtime-test-only";
    private static final String POLICY_WRITER = "nexa_policy_snapshot_writer";
    private static final String POLICY_WRITER_PASSWORD = "tenant-wallet-reservation-policy-test-only";

    @Container
    private static final PostgreSQLContainer TENANT_DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("buyer_wallet_reservation")
            .withUsername("postgres")
            .withPassword("buyer-wallet-reservation-admin-test-only");

    @Autowired
    private ResolveCurrentAccessContextUseCase accessContexts;

    private TenantBusinessDatabaseRouter tenantRouter;
    private TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private TenantBusinessDatabaseAuthority tenantAuthority;
    private TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory configuredPolicyWriter;
    private OperationalSettingsAccess centralOperationalSettings;
    private UUID tenantId;
    private UUID workspaceId;
    private UUID buyerIdentityId;
    private UUID buyerMembershipId;
    private UUID buyerAccountId;
    private UUID databaseIdentity;
    private String credentialReference;

    @BeforeAll
    void prepareTenantWalletDatabase() throws SQLException {
        tenantId = tenantUuid();
        workspaceId = workspaceUuid();
        buyerMembershipId = UUID.fromString(membershipId(BUYER_EMAIL));
        buyerIdentityId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, buyerMembershipId);
        buyerAccountId = UUID.fromString(buyerClientAccountId());
        databaseIdentity = UUID.randomUUID();
        credentialReference = "test/tenant-wallet-reservation/" + tenantId;

        provisionTenantRoles();
        migrateTenant("2");
        seedTenantIdentityAndWorkspace();
        migrateTenant("8");
        seedPolicySnapshot();
        seedBuyerRelationship();

        TenantBusinessDatabaseCredentials credentials = new TenantBusinessDatabaseCredentials(
                TENANT_DATABASE.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
        tenantAuthority = presented -> {
            if (!tenantId.equals(presented.tenantId().value())) {
                throw new org.springframework.security.access.AccessDeniedException("Unrecognized Tenant fixture");
            }
            return new TenantBusinessDatabaseBinding(new TenantId(tenantId), databaseIdentity,
                    credentialReference, TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest());
        };
        TenantBusinessDatabaseDataSourceFactory dataSources = binding -> {
            if (!credentialReference.equals(binding.credentialSecretReference())) {
                throw new IllegalStateException("Unrecognized Tenant fixture credential reference");
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(credentials.jdbcUrl());
            config.setUsername(credentials.username());
            config.setPassword(credentials.password());
            config.setMaximumPoolSize(4);
            config.setPoolName("wallet-reservation-" + binding.databaseIdentity());
            return new HikariDataSource(config);
        };
        tenantRouter = new TenantBusinessDatabaseRouter(tenantAuthority, dataSources, 1);
        centralOperationalSettings = mock(OperationalSettingsAccess.class);
        when(centralOperationalSettings.findPurchaseRequestExpiryPolicy(any(TenantId.class), any(WorkspaceId.class)))
                .thenAnswer(invocation -> Optional.of(new PurchaseRequestExpiryPolicySource(
                        invocation.getArgument(0), invocation.getArgument(1), SourceState.CONFIRMED_ABSENT,
                        null, 3)));
        configuredPolicyWriter = binding -> {
            if (!tenantId.equals(binding.tenantId().value())
                    || !databaseIdentity.equals(binding.databaseIdentity())) {
                throw new TenantBusinessDatabasePolicySnapshotWriterUnavailableException();
            }
            DriverManagerDataSource writer = new DriverManagerDataSource();
            writer.setUrl(TENANT_DATABASE.getJdbcUrl());
            writer.setUsername(POLICY_WRITER);
            writer.setPassword(POLICY_WRITER_PASSWORD);
            return writer;
        };
        expiryPolicies = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                centralOperationalSettings, tenantRouter, tenantAuthority, configuredPolicyWriter);
    }

    @BeforeEach
    void resetWalletAccount() {
        tenantAdminJdbc().update("truncate table payments.buyer_wallet_reservation_event, "
                + "payments.buyer_wallet_ledger_entry, payments.buyer_wallet_reservation, "
                + "payments.buyer_wallet_account");
        seedPreviouslyCreditedFunds(new BigDecimal("100.0000"));
    }

    @AfterAll
    void closeTenantRouter() {
        if (tenantRouter != null) tenantRouter.close();
    }

    @Test
    void reservationsAreScopedIdempotentSerializedAndRefundsCannotExceedConsumption() throws Exception {
        UUID salesMembershipId = UUID.fromString(membershipId(SALES_EMAIL));
        UUID salesIdentityId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, salesMembershipId);
        CurrentAccessContext salesContext = resolveContext(salesIdentityId, Surface.PLATFORM);
        UUID ownerMembership = UUID.fromString(membershipId(OWNER_EMAIL));
        UUID ownerIdentity = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, ownerMembership);
        CurrentAccessContext ownerContext = resolveContext(ownerIdentity, Surface.PLATFORM);

        UUID firstOrder = UUID.randomUUID();
        ReserveCommand firstReserve = reserveCommand(firstOrder, "wallet-reserve-first", "25.0000");
        Reservation held = inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(salesContext, firstReserve));
        Reservation replayedHold = inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(salesContext, firstReserve));
        assertThat(held).isEqualTo(replayedHold);
        assertThat(held.status()).isEqualTo(ReservationStatus.RESERVED);
        ReserveCommand conflictingReplay = reserveCommand(firstOrder, "wallet-reserve-first", "24.0000");
        assertThatThrownBy(() -> inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(salesContext, conflictingReplay)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency key payload conflict");
        assertAccount("100.0000", "25.0000");

        assertThatThrownBy(() -> inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(null, firstReserve)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Verified current access context is required");

        assertThatThrownBy(() -> inTenantTransaction(salesContext, UUID.randomUUID(), workspaceId,
                wallet -> wallet.reserveForSalesOrder(salesContext, firstReserve)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching verified Tenant and Workspace scope");

        TransitionCommand consume = transitionCommand(firstOrder, "wallet-consume-first");
        Reservation consumed = inTenantTransaction(salesContext,
                wallet -> wallet.consumeForPurchaseRequestConversion(salesContext, consume));
        assertThat(consumed.status()).isEqualTo(ReservationStatus.CONSUMED);
        Reservation replayedConsumption = inTenantTransaction(salesContext,
                wallet -> wallet.consumeForPurchaseRequestConversion(salesContext, consume));
        assertThat(replayedConsumption).isEqualTo(consumed);
        assertAccount("75.0000", "0.0000");
        assertThat(count("select count(*) from payments.buyer_wallet_ledger_entry where reservation_id=? and entry_type='ORDER_CONSUMPTION'",
                consumed.id())).isEqualTo(1);

        RefundCommand refund = new RefundCommand(firstOrder, UUID.randomUUID(), new BigDecimal("10.0000"),
                "wallet-refund-first", Instant.now());
        var refunded = inTenantTransaction(ownerContext, wallet -> wallet.refundForSalesOrder(ownerContext, refund));
        BuyerWalletReservationCommands.Refund replayedRefund = inTenantTransaction(ownerContext,
                wallet -> wallet.refundForSalesOrder(ownerContext, refund));
        assertThat(replayedRefund).isEqualTo(refunded);
        assertAccount("85.0000", "0.0000");
        RefundCommand overRefund = new RefundCommand(firstOrder, UUID.randomUUID(), new BigDecimal("16.0000"),
                "wallet-refund-overrun", Instant.now());
        assertThatThrownBy(() -> inTenantTransaction(ownerContext,
                wallet -> wallet.refundForSalesOrder(ownerContext, overRefund)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds consumed funds");
        assertAccount("85.0000", "0.0000");

        UUID secondOrder = UUID.randomUUID();
        ReserveCommand secondReserve = reserveCommand(secondOrder, "wallet-reserve-second", "20.0000");
        Reservation secondHold = inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(salesContext, secondReserve));
        TransitionCommand release = transitionCommand(secondOrder, "wallet-release-second");
        Reservation released = inTenantTransaction(salesContext,
                wallet -> wallet.releaseForSalesOrder(salesContext, release));
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        Reservation replayedRelease = inTenantTransaction(salesContext,
                wallet -> wallet.releaseForSalesOrder(salesContext, release));
        assertThat(replayedRelease).isEqualTo(released);
        assertThat(secondHold.id()).isEqualTo(released.id());
        assertAccount("85.0000", "0.0000");
        assertThatThrownBy(() -> inTenantTransaction(salesContext,
                wallet -> wallet.consumeForSalesOrder(salesContext, release)))
                .isInstanceOf(IllegalStateException.class);

        UUID thirdOrder = UUID.randomUUID();
        UUID fourthOrder = UUID.randomUUID();
        ReserveCommand thirdReserve = reserveCommand(thirdOrder, "wallet-reserve-third", "60.0000");
        ReserveCommand fourthReserve = reserveCommand(fourthOrder, "wallet-reserve-fourth", "60.0000");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = List.of(
                    workers.submit(scopedReserve(salesContext, thirdReserve)),
                    workers.submit(scopedReserve(salesContext, fourthReserve)));
            List<Boolean> outcomes = List.of(attempts.get(0).get(30, TimeUnit.SECONDS),
                    attempts.get(1).get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> !value).count()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertAccount("85.0000", "60.0000");

        ReserveCommand nonBuyerReserve = reserveCommand(UUID.randomUUID(), "wallet-nonbuyer", "1.0000");
        ReserveCommand nonBuyerMembershipReserve = reserveCommand(nonBuyerReserve.salesOrderId(), ownerMembership,
                buyerAccountId, ownerIdentity, nonBuyerReserve.idempotencyKey(), "1.0000");
        assertThatThrownBy(() -> inTenantTransaction(salesContext,
                wallet -> wallet.reserveForSalesOrder(salesContext, nonBuyerMembershipReserve)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active Buyer account relationship is required");

        assertRuntimeScopeIsFailClosed();
        assertAppendOnlyHistory();
    }

    @Test
    void approvedWalletPurchaseRequestConvertsAndConsumesItsFullTotalAtomically() {
        CurrentAccessContext salesContext = salesContext();
        UUID requestId = seedApprovedWalletPurchaseRequest("40.0000");
        long requestVersion = requestVersion(requestId);

        var order = inPolicyTransaction(salesContext, tenantJdbc -> bindTenantSalesComposition(tenantJdbc)
                .salesOrders().convert(salesContext, requestId.toString(), requestVersion,
                        "wallet-convert-" + requestId, "Buyer selected full wallet tender"));

        assertThat(order.status()).isEqualTo("CONFIRMED");
        assertThat(order.paymentOption()).isEqualTo(com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption.WALLET);
        assertThat(order.total()).isEqualByComparingTo("40.0000");
        assertAccount("60.0000", "0.0000");
        assertThat(countRows("select count(*) from sales.purchase_request where id=? and status='CONVERTED'", requestId))
                .isEqualTo(1);
        assertThat(countRows("select count(*) from sales.commercial_commitment where purchase_request_id=? and status='CONVERTED'",
                requestId)).isEqualTo(1);
        assertThat(countRows("select count(*) from payments.buyer_wallet_reservation where sales_order_id=? and status='CONSUMED'",
                UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(countRows("select count(*) from payments.buyer_wallet_ledger_entry where reservation_id=("
                + "select id from payments.buyer_wallet_reservation where sales_order_id=?) and entry_type='ORDER_CONSUMPTION' "
                + "and amount_delta=-40.0000", UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(countRows("select count(*) from payments.buyer_wallet_reservation_event where reservation_id=("
                + "select id from payments.buyer_wallet_reservation where sales_order_id=?) "
                + "and event_type in ('RESERVED','CONSUMED')", UUID.fromString(order.id()))).isEqualTo(2);
        assertThat(tenantAdminJdbc().query("select event_type from payments.buyer_wallet_reservation_event "
                        + "where reservation_id=(select id from payments.buyer_wallet_reservation where sales_order_id=?) "
                        + "order by occurred_at,id", (rs, row) -> rs.getString(1), UUID.fromString(order.id())))
                .containsExactly("RESERVED", "CONSUMED");
        assertThat(countRows("select count(*) from sales.purchase_request_event where purchase_request_id=? and to_status='CONVERTED'",
                requestId)).isEqualTo(1);
        assertThat(countRows("select count(*) from sales.sales_order_event where sales_order_id=? and event_type='ORDER_CREATED'",
                UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(countRows("select count(*) from integration.outbox_event where aggregate_id=? "
                + "and event_type='SALES_ORDER_CONFIRMED'", UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(countRows("select count(*) from sales.idempotency_record where operation=? and idempotency_key=? "
                + "and resource_id=?", "purchase-request-order-conversion", "wallet-convert-" + requestId,
                UUID.fromString(order.id()))).isEqualTo(1);
        assertThat(countRows("select count(*) from integration.change_event where aggregate_id in (?,?) "
                + "and event_type in ('sales.purchase-request.converted','sales.sales-order.created')",
                requestId, UUID.fromString(order.id()))).isEqualTo(2);
    }

    @Test
    void insufficientFundsRollBackPurchaseRequestOrderReservationHistoryAndOutbox() {
        CurrentAccessContext salesContext = salesContext();
        UUID requestId = seedApprovedWalletPurchaseRequest("150.0000");
        long requestVersion = requestVersion(requestId);
        long outboxBefore = countRows("select count(*) from integration.outbox_event where tenant_id=? "
                + "and workspace_id=? and event_type='SALES_ORDER_CONFIRMED'", tenantId, workspaceId);

        assertThatThrownBy(() -> inPolicyTransaction(salesContext, tenantJdbc -> bindTenantSalesComposition(tenantJdbc)
                .salesOrders().convert(salesContext, requestId.toString(), requestVersion,
                        "wallet-insufficient-" + requestId, "Buyer selected full wallet tender")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient available funds");

        assertThat(countRows("select count(*) from sales.sales_order where source_purchase_request_id=?", requestId))
                .isZero();
        assertThat(countRows("select count(*) from sales.purchase_request where id=? and status='APPROVED' and version=?",
                requestId, requestVersion)).isEqualTo(1);
        assertThat(countRows("select count(*) from sales.commercial_commitment where purchase_request_id=? and status='ACTIVE'",
                requestId)).isEqualTo(1);
        assertThat(countRows("select count(*) from sales.purchase_request_event where purchase_request_id=? and to_status='CONVERTED'",
                requestId)).isZero();
        assertThat(countRows("select count(*) from integration.change_event where aggregate_id=? "
                + "and event_type='sales.purchase-request.converted'", requestId)).isZero();
        assertThat(countRows("select count(*) from integration.outbox_event where tenant_id=? "
                + "and workspace_id=? and event_type='SALES_ORDER_CONFIRMED'", tenantId, workspaceId))
                .isEqualTo(outboxBefore);
        assertThat(countRows("select count(*) from sales.idempotency_record where operation=? and idempotency_key=?",
                "purchase-request-order-conversion", "wallet-insufficient-" + requestId)).isZero();
        assertThat(countRows("select count(*) from payments.buyer_wallet_reservation")).isZero();
        assertThat(countRows("select count(*) from payments.buyer_wallet_reservation_event")).isZero();
        assertThat(countRows("select count(*) from payments.buyer_wallet_ledger_entry where entry_type='ORDER_CONSUMPTION'"))
                .isZero();
        assertAccount("100.0000", "0.0000");
    }

    @Test
    void conversionRejectsDifferentClientAccountUuidSnapshotBeforeWriting() {
        CurrentAccessContext salesContext = salesContext();
        UUID requestId = seedApprovedWalletPurchaseRequest("40.0000");
        UUID differentClientAccountId = UUID.randomUUID();
        while (differentClientAccountId.equals(buyerAccountId)) differentClientAccountId = UUID.randomUUID();
        var snapshot = new com.nexa.api.salescommitment.domain.model.salesorder.ApprovedPurchaseRequestSnapshot(
                new TenantId(tenantId), new WorkspaceId(workspaceId),
                new com.nexa.api.customerbuyerrelationships.contract.CustomerAccountId(
                        differentClientAccountId.toString()),
                new com.nexa.api.salescommitment.domain.model.purchaserequest.BuyerMembershipId(buyerMembershipId),
                new com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestId(requestId.toString()),
                List.of(new com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderLine(
                        "wallet-fixture-item", "Wallet fixture item", BigDecimal.ONE, BigDecimal.ONE, "PEN")),
                com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority.NORMAL,
                LocalDate.now().plusDays(2), "Tenant wallet test delivery",
                com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption.WALLET,
                "Buyer selected wallet", "PEN", BigDecimal.ONE);
        var mismatchedAggregate = com.nexa.api.salescommitment.domain.model.salesorder.SalesOrder.fromApprovedSnapshot(
                snapshot,
                new com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderId(UUID.randomUUID().toString()),
                new com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderNumber("SO-2026-654321"),
                new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId(
                        salesContext.membershipId().value()), Instant.now());

        assertThatThrownBy(() -> inPolicyTransaction(salesContext, tenantJdbc -> {
            var persistence = new com.nexa.api.salescommitment.infrastructure.salesorder.SalesOrderPersistenceAdapter(
                    tenantJdbc, null, null, null, null, null, null, java.time.Clock.systemUTC(),
                    new ObjectMapper(), null, null);
            return persistence.persistConversion(mismatchedAggregate, requestVersion(requestId),
                    salesContext.membershipId().toString(), "wallet-wrong-client-" + requestId,
                    "Reject mismatched client snapshot", System.currentTimeMillis(), "unused-test-hash", salesContext);
        }))
                .isInstanceOf(com.nexa.api.salescommitment.application.exception.CommercialBusinessException.class)
                .hasMessage("PURCHASE_REQUEST_SNAPSHOT_CHANGED");

        assertThat(countRows("select count(*) from sales.sales_order where source_purchase_request_id=?", requestId))
                .isZero();
        assertThat(countRows("select count(*) from sales.purchase_request where id=? and status='APPROVED'", requestId))
                .isEqualTo(1);
        assertThat(countRows("select count(*) from payments.buyer_wallet_reservation")).isZero();
    }

    @Test
    void concurrentFullWalletConversionsAllowOnlyOneSalesOrderAndOneAtomicDebit() throws Exception {
        CurrentAccessContext salesContext = salesContext();
        UUID firstRequest = seedApprovedWalletPurchaseRequest("60.0000");
        UUID secondRequest = seedApprovedWalletPurchaseRequest("60.0000");
        long firstVersion = requestVersion(firstRequest);
        long secondVersion = requestVersion(secondRequest);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<ConversionAttempt>> attempts = List.of(
                    workers.submit(() -> convertWalletRequest(salesContext, firstRequest, firstVersion)),
                    workers.submit(() -> convertWalletRequest(salesContext, secondRequest, secondVersion)));
            List<ConversionAttempt> outcomes = List.of(attempts.get(0).get(30, TimeUnit.SECONDS),
                    attempts.get(1).get(30, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(ConversionAttempt::converted).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(outcome -> !outcome.converted()).count()).isEqualTo(1);
            ConversionAttempt converted = outcomes.stream().filter(ConversionAttempt::converted).findFirst().orElseThrow();
            ConversionAttempt rejected = outcomes.stream().filter(outcome -> !outcome.converted()).findFirst().orElseThrow();

            assertAccount("40.0000", "0.0000");
            assertThat(countRows("select count(*) from payments.buyer_wallet_reservation where status='CONSUMED'"))
                    .isEqualTo(1);
            assertThat(countRows("select count(*) from payments.buyer_wallet_ledger_entry where entry_type='ORDER_CONSUMPTION'"))
                    .isEqualTo(1);
            assertThat(countRows("select count(*) from payments.buyer_wallet_reservation_event where event_type in ('RESERVED','CONSUMED')"))
                    .isEqualTo(2);
            assertThat(countRows("select count(*) from sales.sales_order where source_purchase_request_id=?",
                    converted.purchaseRequestId())).isEqualTo(1);
            assertThat(countRows("select count(*) from sales.sales_order where source_purchase_request_id=?",
                    rejected.purchaseRequestId())).isZero();
            assertThat(countRows("select count(*) from sales.purchase_request where id=? and status='APPROVED'",
                    rejected.purchaseRequestId())).isEqualTo(1);
            assertThat(countRows("select count(*) from sales.commercial_commitment where purchase_request_id=? and status='ACTIVE'",
                    rejected.purchaseRequestId())).isEqualTo(1);
            assertThat(countRows("select count(*) from sales.purchase_request_event where purchase_request_id=? and to_status='CONVERTED'",
                    rejected.purchaseRequestId())).isZero();
            assertThat(countRows("select count(*) from integration.outbox_event where aggregate_id=? "
                    + "and event_type='SALES_ORDER_CONFIRMED'", converted.salesOrderId())).isEqualTo(1);
            assertThat(countRows("select count(*) from sales.idempotency_record where operation=? and idempotency_key=? "
                    + "and resource_id=?", "purchase-request-order-conversion",
                    "wallet-concurrent-" + converted.purchaseRequestId(), converted.salesOrderId())).isEqualTo(1);
            assertThat(countRows("select count(*) from integration.change_event where aggregate_id in (?,?) "
                    + "and event_type in ('sales.purchase-request.converted','sales.sales-order.created')",
                    converted.purchaseRequestId(), converted.salesOrderId())).isEqualTo(2);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void walletOrderPaymentCapabilityRequiresFullTenantCompositionAndReadyPolicyProjection() {
        CurrentAccessContext buyerContext = resolveContext(buyerIdentityId, Surface.PORTAL);
        var metrics = new SimpleMeterRegistry();
        var supportedPort = new TenantBoundBuyerWalletReadPort(tenantRouter,
                new JdbcTenantCustomerAccountQueryFactory(), new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                expiryPolicies, fullTenantCompositionProvider(true), metrics);

        RlsRequestScope.set(tenantId, workspaceId);
        try {
            var wallet = new BuyerWalletReadService(supportedPort).getCurrentBuyerWallet(buyerContext, 0, 25);
            assertThat(wallet.capabilities().orderPaymentSupported()).isTrue();

            var disabledPort = new TenantBoundBuyerWalletReadPort(tenantRouter,
                    new JdbcTenantCustomerAccountQueryFactory(), new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                    expiryPolicies, fullTenantCompositionProvider(false), metrics);
            assertThat(disabledPort.orderPaymentSupported(buyerContext)).isFalse();

            OperationalSettingsAccess changedSource = mock(OperationalSettingsAccess.class);
            when(changedSource.findPurchaseRequestExpiryPolicy(any(TenantId.class), any(WorkspaceId.class)))
                    .thenReturn(Optional.of(new PurchaseRequestExpiryPolicySource(new TenantId(tenantId),
                            new WorkspaceId(workspaceId), SourceState.PRESENT, 1L, 4)));
            var writerUnavailablePolicies = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                    changedSource, tenantRouter, tenantAuthority,
                    binding -> { throw new TenantBusinessDatabasePolicySnapshotWriterUnavailableException(); });
            var writerUnavailablePort = new TenantBoundBuyerWalletReadPort(tenantRouter,
                    new JdbcTenantCustomerAccountQueryFactory(), new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                    writerUnavailablePolicies, fullTenantCompositionProvider(true), metrics);
            assertThat(writerUnavailablePort.orderPaymentSupported(buyerContext)).isFalse();
            assertThat(metrics.counter("nexa.buyer.wallet.capability", "feature", "order_payment",
                    "outcome", "unavailable", "reason", "policy_writer_unavailable").count()).isEqualTo(1.0);

            TenantBusinessDatabaseAuthority staleManifestAuthority = presented -> new TenantBusinessDatabaseBinding(
                    new TenantId(tenantId), databaseIdentity, credentialReference);
            var staleManifestPolicies = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                    centralOperationalSettings, tenantRouter, staleManifestAuthority, configuredPolicyWriter);
            var staleManifestPort = new TenantBoundBuyerWalletReadPort(tenantRouter,
                    new JdbcTenantCustomerAccountQueryFactory(), new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                    staleManifestPolicies, fullTenantCompositionProvider(true), metrics);
            assertThat(staleManifestPort.orderPaymentSupported(buyerContext)).isFalse();
            assertThat(metrics.counter("nexa.buyer.wallet.capability", "feature", "order_payment",
                    "outcome", "unavailable", "reason", "schema_manifest_mismatch").count()).isEqualTo(1.0);

            var brokenWriterPolicies = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                    changedSource, tenantRouter, tenantAuthority,
                    binding -> { throw new IllegalStateException("writer factory programming failure"); });
            var brokenWriterPort = new TenantBoundBuyerWalletReadPort(tenantRouter,
                    new JdbcTenantCustomerAccountQueryFactory(), new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                    brokenWriterPolicies, fullTenantCompositionProvider(true), metrics);
            assertThatThrownBy(() -> brokenWriterPort.orderPaymentSupported(buyerContext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("writer factory programming failure");

            RlsRequestScope.clear();
            assertThat(supportedPort.orderPaymentSupported(buyerContext)).isFalse();
            assertThat(metrics.counter("nexa.buyer.wallet.capability", "feature", "order_payment",
                    "outcome", "unavailable", "reason", "verified_scope_unavailable").count()).isEqualTo(1.0);
        } finally {
            RlsRequestScope.clear();
            metrics.close();
        }
    }

    private Callable<Boolean> scopedReserve(CurrentAccessContext context, ReserveCommand command) {
        return () -> {
            try {
                inTenantTransaction(context, wallet -> wallet.reserveForSalesOrder(context, command));
                return true;
            } catch (IllegalStateException insufficientFunds) {
                return false;
            }
        };
    }

    private ConversionAttempt convertWalletRequest(CurrentAccessContext context, UUID requestId, long version) {
        try {
            var order = inPolicyTransaction(context, tenantJdbc -> bindTenantSalesComposition(tenantJdbc)
                    .salesOrders().convert(context, requestId.toString(), version,
                            "wallet-concurrent-" + requestId, "Buyer selected full wallet tender"));
            return new ConversionAttempt(requestId, UUID.fromString(order.id()), true);
        } catch (IllegalStateException failure) {
            if (!failure.getMessage().contains("insufficient available funds")) throw failure;
            return new ConversionAttempt(requestId, null, false);
        }
    }

    private CurrentAccessContext salesContext() {
        UUID salesMembershipId = UUID.fromString(membershipId(SALES_EMAIL));
        UUID salesIdentityId = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, salesMembershipId);
        return resolveContext(salesIdentityId, Surface.PLATFORM);
    }

    private <T> T inPolicyTransaction(CurrentAccessContext context, Function<JdbcTemplate, T> work) {
        RlsRequestScope.set(context.tenantId().value(), context.workspaceId().value());
        try {
            return expiryPolicies.inTransaction(context, work::apply);
        } finally {
            RlsRequestScope.clear();
        }
    }

    private TenantSalesCommitmentCompositionFactory.Composition bindTenantSalesComposition(JdbcTemplate tenantJdbc) {
        var customerAccounts = new ClientAccountPersistenceAdapter(tenantJdbc);
        var sellableSkus = new JdbcTenantSellableSkuQueryFactory().bindTo(tenantJdbc,
                mock(CatalogClientAccountPort.class));
        var traceability = new TenantBusinessTraceabilityBindingsFactory(
                new JdbcTenantBusinessTraceabilityCommandsFactory(new ObjectMapper()),
                (jdbc, commands) -> (tenant, workspace, limit) -> 0).bindTo(tenantJdbc);
        var changeFeed = new JdbcTenantChangeEventPersistenceFactory().bindTo(tenantJdbc);
        var bindings = new TenantSalesCommitmentCompositionFactory.Bindings(
                customerAccounts,
                new JdbcTenantCustomerAddressQueryFactory().bindTo(tenantJdbc),
                sellableSkus,
                mock(CreditExposureQuery.class),
                mock(CatalogItemSnapshotLookupPort.class),
                mock(CreditReservationCommands.class),
                new JdbcTenantWarehouseSelectionQueryFactory().bindTo(tenantJdbc),
                mock(InventoryBackingCommands.class),
                new JdbcBuyerWalletTenantDatabaseAdapterFactory()
                        .bindReservationCommandsTo(tenantJdbc, customerAccounts),
                null, null, null,
                traceability.canonicalOutbox(),
                changeFeed,
                mock(MapRoutingPort.class),
                java.time.Clock.systemUTC(),
                new ObjectMapper(),
                true);
        return new TenantSalesCommitmentCompositionFactoryAdapter(new JdbcTenantCommercialCommitmentFactory())
                .bindTo(tenantJdbc, bindings);
    }

    private TenantSalesCommitmentCompositionProviderAdapter fullTenantCompositionProvider(boolean walletEnabled) {
        ObjectMapper mapper = new ObjectMapper();
        return new TenantSalesCommitmentCompositionProviderAdapter(
                new JdbcTenantCustomerAccountQueryFactory(),
                new JdbcTenantCustomerAddressQueryFactory(),
                new JdbcTenantSellableSkuQueryFactory(),
                new JdbcTenantCatalogItemSnapshotQueryFactory(),
                new JdbcTenantCatalogItemSnapshotLookupFactory(),
                new JdbcTenantCatalogAvailabilityAdapterFactory(),
                new JdbcTenantInventoryBackingCommandsFactory(),
                new JdbcTenantWarehouseSelectionQueryFactory(),
                new JdbcTenantCreditAccountAdapterFactory(
                        new JdbcTenantLegacyCustomerCreditInitializationQueryFactory(), mapper),
                new JdbcTenantPaymentConfirmationQueryFactory(),
                new JdbcBuyerWalletTenantDatabaseAdapterFactory(),
                new TenantBusinessTraceabilityBindingsFactory(
                        new JdbcTenantBusinessTraceabilityCommandsFactory(mapper),
                        (jdbc, commands) -> (tenant, workspace, limit) -> 0),
                new JdbcTenantChangeEventPersistenceFactory(),
                new JdbcTenantSalesOrderFulfillmentQueryFactory(),
                new TenantSalesCommitmentCompositionFactoryAdapter(new JdbcTenantCommercialCommitmentFactory()),
                mock(MapRoutingPort.class), java.time.Clock.systemUTC(), mapper, walletEnabled);
    }

    private UUID seedApprovedWalletPurchaseRequest(String amount) {
        UUID requestId = UUID.randomUUID();
        UUID commitmentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        tenantAdminJdbc().update("insert into sales.purchase_request "
                        + "(id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,"
                        + "requested_delivery_date,delivery_profile_snapshot,payment_option,comments,created_at,updated_at,"
                        + "version,buyer_wallet_beneficiary_identity_id) "
                        + "values (?,?,?,?,?,?,'DRAFT','NORMAL',?,?,'WALLET',?,?,?,0,?)",
                requestId, tenantId, workspaceId, buyerAccountId, buyerMembershipId,
                "WALLET-" + requestId.toString().substring(0, 8), LocalDate.now().plusDays(2),
                "Tenant wallet test delivery", "Buyer selected wallet", now, now, buyerIdentityId);
        tenantAdminJdbc().update("insert into sales.purchase_request_line "
                        + "(id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,"
                        + "unit_price_amount,unit_price_currency,created_at,updated_at) values (?,?,?,?,?,1,'EA',?,'PEN',?,?)",
                UUID.randomUUID(), requestId, "wallet-fixture-item", "Wallet fixture item", "Unit", new BigDecimal(amount),
                now, now);
        tenantAdminJdbc().update("insert into sales.commercial_commitment "
                        + "(id,tenant_id,workspace_id,purchase_request_id,client_account_id,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,'ACTIVE',?,?,0)",
                commitmentId, tenantId, workspaceId, requestId, buyerAccountId, now, now);
        approveFixtureRequest(requestId, now);
        return requestId;
    }

    private void approveFixtureRequest(UUID requestId, Timestamp now) {
        try (Connection connection = tenantAdminConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement revision = connection.prepareStatement(
                    "select set_config('app.expected_purchase_request_expiry_policy_revision', '1', true)")) {
                revision.execute();
            }
            try (PreparedStatement approval = connection.prepareStatement("update sales.purchase_request "
                    + "set status='APPROVED',submitted_at=?,reviewed_at=?,reviewed_by_membership_id=?,updated_at=?,"
                    + "version=version+1 where tenant_id=? and workspace_id=? and id=? and status='DRAFT'")) {
                approval.setTimestamp(1, now);
                approval.setTimestamp(2, now);
                approval.setObject(3, UUID.fromString(membershipId(SALES_EMAIL)));
                approval.setTimestamp(4, now);
                approval.setObject(5, tenantId);
                approval.setObject(6, workspaceId);
                approval.setObject(7, requestId);
                assertThat(approval.executeUpdate()).isEqualTo(1);
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("Tenant wallet fixture approval failed", failure);
        }
    }

    private long requestVersion(UUID requestId) {
        return tenantAdminJdbc().queryForObject("select version from sales.purchase_request where id=?",
                Long.class, requestId);
    }

    private void seedPolicySnapshot() {
        tenantAdminJdbc().update("insert into nexa_platform.purchase_request_expiry_policy_snapshot "
                        + "(tenant_id,workspace_id,source_state,source_version,expiry_days,snapshot_revision,observed_at) "
                        + "values (?,?,'CONFIRMED_ABSENT',null,3,1,current_timestamp)",
                tenantId, workspaceId);
    }

    private CurrentAccessContext resolveContext(UUID userId, Surface surface) {
        RlsRequestScope.set(tenantId, workspaceId);
        try {
            return accessContexts.resolve(new CurrentAccessRequest(new UserId(userId), new TenantId(tenantId),
                    new WorkspaceId(workspaceId), surface));
        } finally {
            RlsRequestScope.clear();
        }
    }

    private <T> T inTenantTransaction(CurrentAccessContext context,
            Function<BuyerWalletReservationCommands, T> work) {
        return inTenantTransaction(context, context.tenantId().value(), context.workspaceId().value(), work);
    }

    private <T> T inTenantTransaction(CurrentAccessContext context, UUID currentTenantId, UUID currentWorkspaceId,
            Function<BuyerWalletReservationCommands, T> work) {
        RlsRequestScope.set(currentTenantId, currentWorkspaceId);
        try {
            return tenantRouter.inTransaction(context, tenantJdbc -> {
                var tenantCustomerAccounts = new ClientAccountPersistenceAdapter(tenantJdbc);
                BuyerWalletReservationCommands commands = new JdbcBuyerWalletTenantDatabaseAdapterFactory()
                        .bindReservationCommandsTo(tenantJdbc, tenantCustomerAccounts);
                return work.apply(commands);
            });
        } finally {
            RlsRequestScope.clear();
        }
    }

    private void seedPreviouslyCreditedFunds(BigDecimal amount) {
        UUID sourceId = UUID.randomUUID();
        JdbcTemplate tenantAdmin = tenantAdminJdbc();
        tenantAdmin.update("insert into payments.buyer_wallet_account "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,posted_balance) values (?,?,?,?,'PEN',?)",
                UUID.randomUUID(), tenantId, workspaceId, buyerIdentityId, amount);
        // Test fixture models a provider-confirmed credit; no public recharge or fake payment is exercised.
        tenantAdmin.update("insert into payments.buyer_wallet_ledger_entry "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,entry_type,amount_delta,source_id,"
                        + "idempotency_key,provider_code,provider_event_id,occurred_at) "
                        + "values (?,?,?,?,'PEN','PROVIDER_RECHARGE',?,?,?,'TEST_PROVIDER',?,?)",
                UUID.randomUUID(), tenantId, workspaceId, buyerIdentityId, amount, sourceId,
                "test-confirmed-wallet-funds-" + sourceId, sourceId.toString(), Timestamp.from(Instant.now()));
    }

    private void provisionTenantRoles() throws SQLException {
        try (Connection connection = tenantAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + POLICY_WRITER + " LOGIN PASSWORD '" + POLICY_WRITER_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + TENANT_DATABASE.getDatabaseName()
                    + "\" TO " + MIGRATOR);
            statement.execute("GRANT CONNECT ON DATABASE \"" + TENANT_DATABASE.getDatabaseName() + "\" TO "
                    + RUNTIME + ", " + POLICY_WRITER);
            statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR);
        }
    }

    private void migrateTenant(String target) {
        Path location = Path.of("src/main/resources/db/tenant-migration").toAbsolutePath().normalize();
        Flyway.configure().dataSource(TENANT_DATABASE.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                .locations("filesystem:" + location).target(target).load().migrate();
    }

    private void seedTenantIdentityAndWorkspace() throws SQLException {
        try (Connection connection = DriverManager.getConnection(TENANT_DATABASE.getJdbcUrl(), MIGRATOR,
                MIGRATOR_PASSWORD);
                PreparedStatement identity = connection.prepareStatement("insert into "
                        + "nexa_platform.tenant_business_database_identity (singleton,tenant_id,database_identity) "
                        + "values (true,?,?)");
                PreparedStatement anchor = connection.prepareStatement("insert into "
                        + "nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) values (?,?)")) {
            identity.setObject(1, tenantId);
            identity.setObject(2, databaseIdentity);
            identity.executeUpdate();
            anchor.setObject(1, tenantId);
            anchor.setObject(2, workspaceId);
            anchor.executeUpdate();
        }
    }

    private void seedBuyerRelationship() {
        Timestamp now = Timestamp.from(Instant.now());
        tenantAdminJdbc().update("insert into sales.client_account "
                        + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                        + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                buyerAccountId, tenantId, workspaceId, "WALLET-" + buyerAccountId.toString().substring(0, 8),
                "Tenant wallet fixture", "Tenant wallet fixture", "PE", "RUC",
                "20" + buyerAccountId.toString().replace("-", "").substring(0, 10), "B2B", "Fixture contact",
                "wallet-fixture@example.test", "+51999999999", "Fixture delivery", "PREPAID", now, now);
        tenantAdminJdbc().update("insert into sales.client_account_membership "
                        + "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
                        + "values (?,?,?,?,?)",
                buyerAccountId, buyerMembershipId, tenantId, workspaceId, now);
    }

    private void assertAccount(String posted, String reserved) {
        AccountBalance balance = tenantAdminJdbc().query("select posted_balance,reserved_balance "
                        + "from payments.buyer_wallet_account where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and currency='PEN'",
                (rs, ignored) -> new AccountBalance(rs.getBigDecimal(1), rs.getBigDecimal(2)),
                tenantId, workspaceId, buyerIdentityId).stream().findFirst().orElseThrow();
        assertThat(balance.posted()).isEqualByComparingTo(posted);
        assertThat(balance.reserved()).isEqualByComparingTo(reserved);
    }

    private long count(String sql, UUID reservationId) {
        return tenantAdminJdbc().queryForObject(sql, Long.class, reservationId);
    }

    private long countRows(String sql, Object... arguments) {
        return tenantAdminJdbc().queryForObject(sql, Long.class, arguments);
    }

    private void assertRuntimeScopeIsFailClosed() throws SQLException {
        try (Connection connection = tenantRuntimeConnection()) {
            connection.setAutoCommit(false);
            setScope(connection, tenantId, workspaceId);
            assertThat(countWalletAccounts(connection)).isEqualTo(1);
            setScope(connection, tenantId, UUID.randomUUID());
            assertThat(countWalletAccounts(connection)).isZero();
            setScope(connection, null, null);
            assertThat(countWalletAccounts(connection)).isZero();
            setScope(connection, tenantId, UUID.randomUUID());
            try (PreparedStatement insert = connection.prepareStatement("insert into payments.buyer_wallet_account "
                    + "(id,tenant_id,workspace_id,buyer_identity_id,currency) values (?,?,?,?, 'PEN')")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, tenantId);
                insert.setObject(3, workspaceId);
                insert.setObject(4, UUID.randomUUID());
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
            }
            connection.rollback();
        }
    }

    private void assertAppendOnlyHistory() {
        UUID ledgerEntry = tenantAdminJdbc().queryForObject("select id from payments.buyer_wallet_ledger_entry "
                        + "where tenant_id=? and workspace_id=? order by occurred_at desc limit 1",
                UUID.class, tenantId, workspaceId);
        assertThatThrownBy(() -> tenantAdminJdbc().update(
                "update payments.buyer_wallet_ledger_entry set source_id=? where id=?", UUID.randomUUID(), ledgerEntry))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> {
                    Throwable rootCause = failure;
                    while (rootCause.getCause() != null) rootCause = rootCause.getCause();
                    assertThat(rootCause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("55000");
                    assertThat(rootCause.getMessage())
                            .contains("Buyer wallet ledger and reservation events are append-only");
                });
    }

    private static long countWalletAccounts(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("select count(*) from payments.buyer_wallet_account");
                ResultSet results = query.executeQuery()) {
            results.next();
            return results.getLong(1);
        }
    }

    private static void setScope(Connection connection, UUID tenantId, UUID workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('app.current_tenant_id', ?, true), set_config('app.current_workspace_id', ?, true)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.setString(2, workspaceId == null ? "" : workspaceId.toString());
            statement.execute();
        }
    }

    private UUID orderId() { return UUID.randomUUID(); }

    private ReserveCommand reserveCommand(UUID orderId, String key, String amount) {
        return reserveCommand(orderId, buyerMembershipId, buyerAccountId, buyerIdentityId, key, amount);
    }

    private ReserveCommand reserveCommand(UUID orderId, UUID membershipId, UUID accountId, UUID identityId,
            String key, String amount) {
        return new ReserveCommand(orderId, membershipId, accountId, identityId, new BigDecimal(amount), key,
                Instant.now());
    }

    private TransitionCommand transitionCommand(UUID orderId, String key) {
        return new TransitionCommand(orderId, key, Instant.now());
    }

    private JdbcTemplate tenantAdminJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(TENANT_DATABASE.getJdbcUrl(),
                TENANT_DATABASE.getUsername(), TENANT_DATABASE.getPassword()));
    }

    private Connection tenantAdminConnection() throws SQLException {
        return DriverManager.getConnection(TENANT_DATABASE.getJdbcUrl(), TENANT_DATABASE.getUsername(),
                TENANT_DATABASE.getPassword());
    }

    private Connection tenantRuntimeConnection() throws SQLException {
        return DriverManager.getConnection(TENANT_DATABASE.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private record ConversionAttempt(UUID purchaseRequestId, UUID salesOrderId, boolean converted) { }

    private record AccountBalance(BigDecimal posted, BigDecimal reserved) { }
}
