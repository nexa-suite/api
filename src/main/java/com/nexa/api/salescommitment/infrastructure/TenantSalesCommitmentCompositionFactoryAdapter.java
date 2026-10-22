package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import com.nexa.api.salescommitment.application.service.FieldPurchaseRequestSubmissionService;
import com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTime;
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
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderService;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderDraftService;
import com.nexa.api.salescommitment.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.salescommitment.tenantdatabase.TenantCommercialCommitmentFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery;
import com.nexa.api.salescommitment.infrastructure.clientaccount.ClientAccountCommercialAclAdapter;
import com.nexa.api.salescommitment.infrastructure.reference.JdbcPeruGeographyPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.salesorder.ManualSalesOrderPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.salesorder.ManualSalesOrderDraftPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.seed.SellableSkuSnapshotPersistenceAdapter;
import com.nexa.api.salescommitment.infrastructure.warehouse.WarehouseReferenceAclAdapter;
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
        MinimumDeliveryLeadTime minimumDeliveryLeadTime = new MinimumDeliveryLeadTime(
                dependencies.minimumDeliveryLeadDays());
        var commitmentPort = commitments.bindTo(jdbc, new TenantCommercialCommitmentFactory.Bindings(
                dependencies.sellableSkus(), dependencies.customerAccounts(), dependencies.creditReservations(),
                dependencies.inventoryBacking(), dependencies.paymentConfirmations(), dependencies.receivables(),
                dependencies.clock(), dependencies.canonicalOutbox()));
        var persistence = new SalesOrderPersistenceAdapter(jdbc, dependencies.changeFeed(), commitmentPort,
                dependencies.customerAccounts(), dependencies.paymentConfirmations(), dependencies.receivables(),
                dependencies.financialAdjustments(), dependencies.clock(), dependencies.objectMapper(),
                dependencies.canonicalOutbox(), dependencies.walletReservations());
        IdempotencyPersistencePort idempotency = new IdempotencyPersistenceAdapter(jdbc);
        var salesOrders = new SalesOrderService(persistence, dependencies.customerAccounts(), persistence,
                persistence, idempotency, dependencies.objectMapper(), persistence,
                dependencies.walletReservations(), dependencies.walletRefundBalances());
        DirectOrderUseCase directOrders = new DirectOrderService(commitmentPort, persistence,
                dependencies.clock(), idempotency, dependencies.objectMapper(),
                dependencies.sellableSkus(), minimumDeliveryLeadTime);
        var purchaseRequestPersistence = new PurchaseRequestPersistenceAdapter(jdbc);
        var purchaseRequests = new PurchaseRequestService(purchaseRequestPersistence,
                new PurchaseRequestEventPersistenceAdapter(jdbc, dependencies.canonicalOutbox()),
                idempotency, dependencies.catalogItemSnapshots(),
                dependencies.customerAccounts(), dependencies.changeFeed(), commitmentPort,
                dependencies.clock(), dependencies.objectMapper(),
                new JdbcMaterialChangePersistenceAdapter(jdbc, purchaseRequestPersistence,
                        dependencies.objectMapper()), dependencies.walletTenderEnabled(), minimumDeliveryLeadTime);
        var drafts = new PurchaseRequestDraftService(jdbc, dependencies.objectMapper(), commitmentPort,
                dependencies.maps(), dependencies.customerAccounts(), customerTerms(dependencies.customerAccounts()),
                dependencies.customerAddresses(),
                dependencies.creditExposure(), dependencies.sellableSkus(), dependencies.warehouses(),
                dependencies.canonicalOutbox(), dependencies.walletTenderEnabled(), minimumDeliveryLeadTime,
                dependencies.clock());
        var manualOrderPersistence = new ManualSalesOrderPersistenceAdapter(jdbc, dependencies.objectMapper());
        ManualSalesOrderUseCase manualSalesOrders = new ManualSalesOrderService(
                new SalesSnapshotAssembler(
                        new ClientAccountCommercialAclAdapter(dependencies.customerAccounts(),
                                customerTerms(dependencies.customerAccounts()), dependencies.creditExposure()),
                        dependencies.customerAddresses(), new WarehouseReferenceAclAdapter(dependencies.warehouses()),
                        new JdbcPeruGeographyPersistenceAdapter(jdbc), dependencies.maps(),
                        dependencies.catalogItemSnapshots(),
                        new SellableSkuSnapshotPersistenceAdapter(dependencies.sellableSkus())), manualOrderPersistence,
                minimumDeliveryLeadTime, dependencies.clock());
        ManualSalesOrderDraftUseCase manualSalesOrderDrafts = new ManualSalesOrderDraftService(
                new ManualSalesOrderDraftPersistenceAdapter(jdbc, dependencies.objectMapper(), dependencies.maps(),
                        dependencies.customerAccounts(), dependencies.customerAddresses(),
                        customerTerms(dependencies.customerAccounts()), dependencies.creditExposure(),
                        dependencies.sellableSkus(), dependencies.warehouses()),
                manualSalesOrders, manualOrderPersistence, minimumDeliveryLeadTime, dependencies.clock());
        FieldPurchaseRequestSubmissionUseCase fieldPurchaseRequestSubmissions =
                new FieldPurchaseRequestSubmissionService(purchaseRequests, dependencies.customerAccounts(),
                        dependencies.catalogItemSnapshots(), idempotency, dependencies.objectMapper(),
                        dependencies.clock());
        return new Composition(drafts, purchaseRequests, salesOrders, salesOrders, directOrders,
                manualSalesOrders, manualSalesOrderDrafts, fieldPurchaseRequestSubmissions);
    }

    private static CustomerTermsQuery customerTerms(
            com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery accounts) {
        if (accounts instanceof CustomerTermsQuery terms) return terms;
        throw new IllegalArgumentException(
                "Tenant Customer Account binding must provide Customer Terms on the same JDBC session");
    }
}
