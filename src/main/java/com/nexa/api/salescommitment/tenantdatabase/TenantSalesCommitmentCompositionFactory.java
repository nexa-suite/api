package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderApprovedWorkflowConversion;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.publicapi.MapRoutingPort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Composes BC-04 Buyer request and Sales Order commands on one Tenant JDBC session. */
@FunctionalInterface
public interface TenantSalesCommitmentCompositionFactory {
    Composition bindTo(JdbcTemplate tenantJdbc, Bindings bindings);

    record Bindings(CustomerAccountQuery customerAccounts, CustomerAddressQuery customerAddresses,
                    SellableSkuQuery sellableSkus, CreditExposureQuery creditExposure,
                    CatalogItemSnapshotLookupPort catalogItemSnapshots,
                    CreditReservationCommands creditReservations, WarehouseSelectionQuery warehouses,
                    InventoryBackingCommands inventoryBacking,
                    BuyerWalletReservationCommands walletReservations,
                    PaymentConfirmationQuery paymentConfirmations, ReceivableCommands receivables,
                    FinancialAdjustmentCommands financialAdjustments,
                    CanonicalOutboxPort canonicalOutbox, ChangeEventPersistencePort changeFeed,
                    MapRoutingPort maps, Clock clock, ObjectMapper objectMapper,
                    boolean walletTenderEnabled) {
        public Bindings {
            if (customerAccounts == null || customerAddresses == null || sellableSkus == null
                    || creditExposure == null || catalogItemSnapshots == null || creditReservations == null || warehouses == null
                    || inventoryBacking == null || canonicalOutbox == null || changeFeed == null
                    || clock == null || objectMapper == null) {
                throw new IllegalArgumentException("Tenant Sales Commitment bindings are incomplete");
            }
            if (walletTenderEnabled && walletReservations == null) {
                throw new IllegalArgumentException("Tenant Buyer wallet commands are required when wallet tender is enabled");
            }
            if (paymentConfirmations != null && financialAdjustments == null) {
                throw new IllegalArgumentException("Financial adjustment commands are required with payment confirmation queries");
            }
        }
    }

    record Composition(PurchaseRequestDraftPort purchaseRequestDrafts, PurchaseRequestUseCase purchaseRequests,
                       SalesOrderUseCase salesOrders, SalesOrderApprovedWorkflowConversion approvedWorkflowConversion,
                       DirectOrderUseCase directOrders) {
        public Composition {
            if (purchaseRequestDrafts == null || purchaseRequests == null || salesOrders == null
                    || approvedWorkflowConversion == null || directOrders == null) {
                throw new IllegalArgumentException("Tenant Sales Commitment composition is incomplete");
            }
        }
    }
}
