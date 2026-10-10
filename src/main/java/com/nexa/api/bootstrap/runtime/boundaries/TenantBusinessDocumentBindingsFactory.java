package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.DocumentSubjectLookupPort;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentServiceFactory;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerPort;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogDocumentSourceQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDocumentSourceQueryFactory;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDocumentSourceQueryFactory;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInboundReceivingDiscrepancySubjectQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import com.nexa.api.payments.tenantdatabase.TenantPaymentDocumentSourceQueryFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesDocumentSourceQueryFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Composes BC-09 only from owner ports bound to the current Tenant session. */
@Component
@Profile("local")
public final class TenantBusinessDocumentBindingsFactory {
    private final TenantBusinessDocumentServiceFactory documents;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantCreditAccountAdapterFactory creditAccounts;
    private final TenantCatalogDocumentSourceQueryFactory catalog;
    private final TenantSalesDocumentSourceQueryFactory sales;
    private final TenantPaymentDocumentSourceQueryFactory payments;
    private final TenantFulfillmentDocumentSourceQueryFactory fulfillment;
    private final TenantInboundReceivingDiscrepancySubjectQueryFactory inboundDiscrepancies;
    private final TenantWarehouseSelectionQueryFactory warehouseSelection;
    private final TenantBusinessEvidenceQueryFactory businessEvidence;
    private final TenantBusinessTraceabilityBindingsFactory traceability;

    public TenantBusinessDocumentBindingsFactory(TenantBusinessDocumentServiceFactory documents,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantCreditAccountAdapterFactory creditAccounts,
            TenantCatalogDocumentSourceQueryFactory catalog,
            TenantSalesDocumentSourceQueryFactory sales,
            TenantPaymentDocumentSourceQueryFactory payments,
            TenantFulfillmentDocumentSourceQueryFactory fulfillment,
            TenantInboundReceivingDiscrepancySubjectQueryFactory inboundDiscrepancies,
            TenantWarehouseSelectionQueryFactory warehouseSelection,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantBusinessTraceabilityBindingsFactory traceability) {
        this.documents = Objects.requireNonNull(documents);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.creditAccounts = Objects.requireNonNull(creditAccounts);
        this.catalog = Objects.requireNonNull(catalog);
        this.sales = Objects.requireNonNull(sales);
        this.payments = Objects.requireNonNull(payments);
        this.fulfillment = Objects.requireNonNull(fulfillment);
        this.inboundDiscrepancies = Objects.requireNonNull(inboundDiscrepancies);
        this.warehouseSelection = Objects.requireNonNull(warehouseSelection);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.traceability = Objects.requireNonNull(traceability);
    }

    public TenantBusinessDocumentServiceFactory.Bindings bindRequest(JdbcTemplate tenantJdbc,
            CurrentAccessContext accessContext, Set<UUID> activeWarehouseIds) {
        Objects.requireNonNull(accessContext, "Verified access context is required");
        UUID tenantId = accessContext.tenantId().value();
        UUID workspaceId = accessContext.workspaceId().value();
        CustomerAccountQuery tenantAccounts = required(customerAccounts.bindTo(tenantJdbc));
        ReceivablePaymentAccess tenantReceivables = required(creditAccounts.bindReceivablePaymentAccessTo(tenantJdbc));
        SalesDocumentSourceQuery tenantSales = required(sales.bindTo(tenantJdbc));
        PaymentDocumentSourceQuery tenantPayments = required(payments.bindTo(tenantJdbc));
        FulfillmentDocumentSourceQuery tenantFulfillment = required(fulfillment.bindTo(tenantJdbc));
        WarehouseSelectionQuery tenantWarehouses = required(warehouseSelection.bindTo(tenantJdbc));
        WarehouseObjectAccess warehouseSnapshot = TenantWarehouseRequestBindings.warehouseAccessSnapshot(
                accessContext, Objects.requireNonNull(activeWarehouseIds));
        InboundReceivingDiscrepancySubjectQuery tenantDiscrepancies = required(
                inboundDiscrepancies.bindTo(tenantJdbc, warehouseSnapshot));
        DocumentSubjectLookupPort subjects = new DocumentSubjectCompositionAdapter(tenantSales,
                tenantReceivables, tenantPayments, tenantFulfillment, tenantDiscrepancies, tenantWarehouses);
        DocumentProjectionLookupPort projections = projections(tenantId, workspaceId, tenantJdbc,
                tenantSales, tenantAccounts, tenantReceivables, tenantPayments, tenantFulfillment, Optional.empty());
        CanonicalOutboxPort outbox = traceability.bindTo(tenantJdbc).canonicalOutbox();
        return required(documents.bindTo(tenantJdbc, subjects, projections, tenantAccounts, outbox));
    }

    public WorkerBindings bindWorker(JdbcTemplate tenantJdbc, UUID tenantId,
            UUID workspaceId, OrganizationDocumentSourceQuery.Snapshot organizationSnapshot) {
        Objects.requireNonNull(organizationSnapshot, "Organization document snapshot is required");
        CustomerAccountQuery tenantAccounts = required(customerAccounts.bindTo(tenantJdbc));
        ReceivablePaymentAccess tenantReceivables = required(creditAccounts.bindReceivablePaymentAccessTo(tenantJdbc));
        SalesDocumentSourceQuery tenantSales = required(sales.bindTo(tenantJdbc));
        PaymentDocumentSourceQuery tenantPayments = required(payments.bindTo(tenantJdbc));
        FulfillmentDocumentSourceQuery tenantFulfillment = required(fulfillment.bindTo(tenantJdbc));
        DocumentProjectionLookupPort projections = projections(tenantId, workspaceId, tenantJdbc,
                tenantSales, tenantAccounts, tenantReceivables, tenantPayments, tenantFulfillment,
                Optional.of(organizationSnapshot));
        CanonicalOutboxPort outbox = traceability.bindTo(tenantJdbc).canonicalOutbox();
        return new WorkerBindings(required(documents.bindWorkerTo(tenantJdbc, projections, tenantAccounts, outbox)));
    }

    public WorkerBindings bindEvidenceWorker(JdbcTemplate tenantJdbc) {
        CustomerAccountQuery tenantAccounts = required(customerAccounts.bindTo(tenantJdbc));
        CanonicalOutboxPort outbox = traceability.bindTo(tenantJdbc).canonicalOutbox();
        DocumentProjectionLookupPort unavailableProjection = (tenantId, workspaceId, subject, type) -> {
            throw new IllegalStateException("Document generation requires a preflighted organization snapshot");
        };
        return new WorkerBindings(required(documents.bindWorkerTo(tenantJdbc, unavailableProjection,
                tenantAccounts, outbox)));
    }

    private DocumentProjectionLookupPort projections(UUID tenantId, UUID workspaceId, JdbcTemplate tenantJdbc,
            SalesDocumentSourceQuery tenantSales, CustomerAccountQuery tenantAccounts,
            ReceivablePaymentAccess tenantReceivables, PaymentDocumentSourceQuery tenantPayments,
            FulfillmentDocumentSourceQuery tenantFulfillment,
            Optional<OrganizationDocumentSourceQuery.Snapshot> organizationSnapshot) {
        CatalogDocumentSourceQuery tenantCatalog = required(catalog.bindTo(tenantJdbc));
        var evidence = required(businessEvidence.bindTo(tenantJdbc));
        OrganizationDocumentSourceQuery exactSnapshot = (requestedTenant, requestedWorkspace) ->
                tenantId.equals(requestedTenant) && workspaceId.equals(requestedWorkspace)
                        ? organizationSnapshot : Optional.empty();
        return new DocumentProjectionCompositionAdapter(tenantSales, tenantCatalog, tenantAccounts,
                exactSnapshot, tenantFulfillment, tenantPayments, tenantReceivables, evidence);
    }

    private static <T> T required(T value) {
        return Objects.requireNonNull(value, "Tenant business-document composition returned no binding");
    }

    public record WorkerBindings(TenantBusinessDocumentWorkerPort worker) {
        public WorkerBindings {
            Objects.requireNonNull(worker, "Tenant document worker is required");
        }
    }
}
