package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.service.PurchaseRequestService;
import com.nexa.api.salescommitment.infrastructure.purchaserequest.JdbcMaterialChangePersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.purchaserequest.PurchaseRequestEventPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.purchaserequest.PurchaseRequestPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.idempotency.IdempotencyPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.purchaserequestdraft.PurchaseRequestDraftService;
import com.nexa.api.salescommitment.infrastructure.salesorder.SalesOrderPersistenceAdapter;
import com.nexa.api.salescommitment.application.salesorder.service.SalesOrderService;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.directorder.service.DirectOrderService;
import com.nexa.api.salescommitment.tenantdatabase.TenantCommercialCommitmentFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Binds existing BC-04 adapters and orchestration to the supplied callback session.
 * No adapter opened here may resolve a central database connection.
 */
@Component
@Profile("!test")
public final class TenantSalesCommitmentCompositionFactoryAdapter
        implements TenantSalesCommitmentCompositionFactory {
    private final TenantCommercialCommitmentFactory commitments;

    public TenantSalesCommitmentCompositionFactoryAdapter(TenantCommercialCommitmentFactory commitments) {
        this.commitments = Objects.requireNonNull(commitments,
                "Tenant Commercial Commitment factory is required");
    }

    @Override
    public Composition bindTo(JdbcTemplate tenantJdbc, Bindings bindings) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        Bindings dependencies = Objects.requireNonNull(bindings, "Tenant Sales Commitment bindings are required");
        var commitmentPort = commitments.bindTo(jdbc, new TenantCommercialCommitmentFactory.Bindings(
                dependencies.sellableSkus(), dependencies.customerAccounts(), dependencies.creditReservations(),
                dependencies.inventoryBacking(), dependencies.paymentConfirmations(), dependencies.receivables(),
                dependencies.clock(), dependencies.canonicalOutbox()));
        var persistence = new SalesOrderPersistenceAdapter(jdbc, dependencies.changeFeed(), commitmentPort,
                dependencies.customerAccounts(), dependencies.paymentConfirmations(), dependencies.receivables(),
                dependencies.financialAdjustments(), dependencies.clock(), dependencies.objectMapper(),
                dependencies.canonicalOutbox(), dependencies.walletReservations());
        var salesOrders = new SalesOrderService(persistence, dependencies.customerAccounts(), persistence,
                persistence, new IdempotencyPersistenceAdapter(jdbc), dependencies.objectMapper());
        DirectOrderUseCase directOrders = new DirectOrderService(commitmentPort, persistence,
                dependencies.clock(), new IdempotencyPersistenceAdapter(jdbc), dependencies.objectMapper(),
                dependencies.sellableSkus());
        var purchaseRequestPersistence = new PurchaseRequestPersistenceAdapter(jdbc);
        var purchaseRequests = new PurchaseRequestService(purchaseRequestPersistence,
                new PurchaseRequestEventPersistenceAdapter(jdbc, dependencies.canonicalOutbox()),
                new IdempotencyPersistenceAdapter(jdbc), dependencies.catalogItemSnapshots(),
                dependencies.customerAccounts(), dependencies.changeFeed(), commitmentPort,
                dependencies.clock(), dependencies.objectMapper(),
                new JdbcMaterialChangePersistenceAdapter(jdbc, purchaseRequestPersistence,
                        dependencies.objectMapper()), dependencies.walletTenderEnabled());
        var drafts = new PurchaseRequestDraftService(jdbc, dependencies.objectMapper(), commitmentPort,
                dependencies.maps(), dependencies.customerAccounts(), dependencies.customerAddresses(),
                dependencies.creditExposure(), dependencies.sellableSkus(), dependencies.warehouses(),
                dependencies.canonicalOutbox(), dependencies.walletTenderEnabled());
        return new Composition(drafts, purchaseRequests, salesOrders, salesOrders, directOrders);
    }
}
