package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogItemSnapshotQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAddressQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryBackingCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.application.publicapi.MapRoutingPort;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantCatalogItemSnapshotLookupFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Composes all owner-published business ports against the exact Tenant router callback session.
 * No central persistence adapter or data source is consulted by this binding.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class TenantSalesCommitmentCompositionProviderAdapter
        implements TenantSalesCommitmentCompositionProvider {
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantCustomerAddressQueryFactory customerAddresses;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantCatalogItemSnapshotQueryFactory catalogSnapshots;
    private final TenantCatalogItemSnapshotLookupFactory catalogSnapshotLookup;
    private final TenantCatalogAvailabilityAdapterFactory catalogAvailability;
    private final TenantInventoryBackingCommandsFactory inventoryBacking;
    private final TenantWarehouseSelectionQueryFactory warehouses;
    private final TenantCreditAccountAdapterFactory creditAdapters;
    private final TenantPaymentConfirmationQueryFactory paymentConfirmations;
    private final BuyerWalletTenantDatabaseAdapterFactory walletAdapters;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final TenantChangeEventPersistenceFactory changeEvents;
    private final TenantSalesOrderFulfillmentQueryFactory fulfillmentQueries;
    private final TenantSalesCommitmentCompositionFactory salesCommitments;
    private final MapRoutingPort maps;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final boolean walletTenderEnabled;

    public TenantSalesCommitmentCompositionProviderAdapter(
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantCustomerAddressQueryFactory customerAddresses,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantCatalogItemSnapshotQueryFactory catalogSnapshots,
            TenantCatalogItemSnapshotLookupFactory catalogSnapshotLookup,
            TenantCatalogAvailabilityAdapterFactory catalogAvailability,
            TenantInventoryBackingCommandsFactory inventoryBacking,
            TenantWarehouseSelectionQueryFactory warehouses,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantPaymentConfirmationQueryFactory paymentConfirmations,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            TenantChangeEventPersistenceFactory changeEvents,
            TenantSalesOrderFulfillmentQueryFactory fulfillmentQueries,
            TenantSalesCommitmentCompositionFactory salesCommitments,
            MapRoutingPort maps, Clock clock, ObjectMapper objectMapper,
            @Value("${nexa.tenant-business.purchase-request.wallet-tender-enabled:false}") boolean walletTenderEnabled) {
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.customerAddresses = Objects.requireNonNull(customerAddresses);
        this.sellableSkus = Objects.requireNonNull(sellableSkus);
        this.catalogSnapshots = Objects.requireNonNull(catalogSnapshots);
        this.catalogSnapshotLookup = Objects.requireNonNull(catalogSnapshotLookup);
        this.catalogAvailability = Objects.requireNonNull(catalogAvailability);
        this.inventoryBacking = Objects.requireNonNull(inventoryBacking);
        this.warehouses = Objects.requireNonNull(warehouses);
        this.creditAdapters = Objects.requireNonNull(creditAdapters);
        this.paymentConfirmations = Objects.requireNonNull(paymentConfirmations);
        this.walletAdapters = Objects.requireNonNull(walletAdapters);
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings);
        this.changeEvents = Objects.requireNonNull(changeEvents);
        this.fulfillmentQueries = Objects.requireNonNull(fulfillmentQueries);
        this.salesCommitments = Objects.requireNonNull(salesCommitments);
        this.maps = Objects.requireNonNull(maps);
        this.clock = Objects.requireNonNull(clock);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.walletTenderEnabled = walletTenderEnabled;
    }

    @Override
    public TenantSalesCommitmentCompositionFactory.Composition bindTo(JdbcTemplate tenantJdbc) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant router callback JDBC session is required");
        CustomerAccountQuery tenantAccounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        CatalogClientAccountPort tenantCatalogAccounts = catalogClientAccounts(tenantAccounts);
        SellableSkuQuery tenantSkus = Objects.requireNonNull(sellableSkus.bindTo(jdbc, tenantCatalogAccounts),
                "Tenant Catalog factory returned no SKU query");
        ProductAvailabilityPort tenantAvailability = Objects.requireNonNull(
                catalogAvailability.bindTo(jdbc, tenantSkus), "Tenant Inventory factory returned no availability query");
        GetCatalogItemSnapshotUseCase snapshotQuery = Objects.requireNonNull(
                catalogSnapshots.bindTo(jdbc, tenantCatalogAccounts, tenantAvailability),
                "Tenant Catalog factory returned no snapshot query");
        var tenantSnapshots = Objects.requireNonNull(catalogSnapshotLookup.bindTo(snapshotQuery),
                "Tenant Sales factory returned no Catalog snapshot lookup");

        var traceability = traceabilityBindings.bindTo(jdbc);
        ReceivablePaymentAccess tenantReceivableAccess = Objects.requireNonNull(
                creditAdapters.bindReceivablePaymentAccessTo(jdbc),
                "Tenant Credit factory returned no receivable access");
        PaymentConfirmationQuery tenantPaymentConfirmations = Objects.requireNonNull(
                paymentConfirmations.bindTo(jdbc, tenantReceivableAccess),
                "Tenant Payments factory returned no confirmation query");
        SalesOrderFulfillmentQuery tenantFulfillment = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc),
                "Tenant Sales factory returned no fulfillment query");
        FinancialAdjustmentSource adjustmentSource = adjustmentSource(tenantFulfillment, tenantPaymentConfirmations);
        TenantCreditAccountAdapterFactory.Bindings tenantCredit = Objects.requireNonNull(
                creditAdapters.bindTo(jdbc, tenantAccounts, tenantReceivableAccess, traceability.commands(),
                        traceability.canonicalOutbox(), adjustmentSource),
                "Tenant Credit factory returned no bindings");

        InventoryBackingCommands tenantBacking = Objects.requireNonNull(inventoryBacking.bindTo(jdbc, tenantSkus),
                "Tenant Inventory factory returned no backing commands");
        WarehouseSelectionQuery tenantWarehouses = Objects.requireNonNull(warehouses.bindTo(jdbc),
                "Tenant Warehouse factory returned no query");
        BuyerWalletReservationCommands tenantWallet = Objects.requireNonNull(
                walletAdapters.bindReservationCommandsTo(jdbc, tenantAccounts),
                "Tenant Payments factory returned no wallet reservations");
        ChangeEventPersistencePort tenantChangeFeed = Objects.requireNonNull(changeEvents.bindTo(jdbc),
                "Tenant change-feed factory returned no adapter");

        var bindings = new TenantSalesCommitmentCompositionFactory.Bindings(
                tenantAccounts, customerAddresses.bindTo(jdbc), tenantSkus, tenantCredit.creditExposure(),
                tenantSnapshots, tenantCredit.creditReservations(), tenantWarehouses, tenantBacking, tenantWallet,
                tenantPaymentConfirmations, tenantCredit.receivables(), tenantCredit.financialAdjustments(),
                traceability.canonicalOutbox(), tenantChangeFeed, maps, clock, objectMapper, walletTenderEnabled);
        return salesCommitments.bindTo(jdbc, bindings);
    }

    @Override
    public boolean walletOrderPaymentEnabled() {
        return walletTenderEnabled;
    }

    private static FinancialAdjustmentSource adjustmentSource(SalesOrderFulfillmentQuery salesOrders,
            PaymentConfirmationQuery payments) {
        return new FinancialAdjustmentSource() {
            @Override
            public Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                SalesOrderFulfillmentQuery.Snapshot order = salesOrders.getForUpdate(tenantId, workspaceId,
                        salesOrderId);
                return new Snapshot(order.status(), order.currency(), order.total());
            }

            @Override
            public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return payments.hasSuccessfulPayment(tenantId, workspaceId, salesOrderId);
            }
        };
    }

    private static CatalogClientAccountPort catalogClientAccounts(CustomerAccountQuery accounts) {
        return new CatalogClientAccountPort() {
            @Override
            public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
                return accounts.findUnfilteredReferenceForMembership(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(UUID::fromString);
            }

            @Override
            public Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId,
                    UUID membershipId) {
                return accounts.findActiveBuyerDetails(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(TenantSalesCommitmentCompositionProviderAdapter::profile);
            }

            @Override
            public Optional<ClientAccountProfile> findActiveProfile(UUID tenantId, UUID workspaceId,
                    UUID customerAccountId) {
                return accounts.findActiveDetails(tenantId.toString(), workspaceId.toString(),
                        customerAccountId.toString()).map(TenantSalesCommitmentCompositionProviderAdapter::profile);
            }
        };
    }

    private static CatalogClientAccountPort.ClientAccountProfile profile(CustomerAccountDetails account) {
        return new CatalogClientAccountPort.ClientAccountProfile(UUID.fromString(account.id()), account.segment(), null);
    }
}
