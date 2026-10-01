package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections.BusinessParty;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections.DeliveryInfo;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentProjections.DocumentTotals;
import com.nexa.api.businessdocuments.application.publicapi.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.domain.publicapi.BusinessDocumentType;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectType;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogDocumentSourceQuery;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only ACL composed from owner-provided immutable document source snapshots. */
@Repository
@Profile("!test")
public class DocumentProjectionCompositionAdapter implements DocumentProjectionLookupPort {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final SalesDocumentSourceQuery sales;
    private final CatalogDocumentSourceQuery catalog;
    private final CustomerAccountQuery customerAccounts;
    private final OrganizationDocumentSourceQuery organization;
    private final FulfillmentDocumentSourceQuery fulfillment;
    private final PaymentDocumentSourceQuery payments;
    private final ReceivablePaymentAccess receivables;
    private final BusinessEvidenceQuery businessEvidence;

    public DocumentProjectionCompositionAdapter(SalesDocumentSourceQuery sales,
            CatalogDocumentSourceQuery catalog, CustomerAccountQuery customerAccounts,
            OrganizationDocumentSourceQuery organization, FulfillmentDocumentSourceQuery fulfillment,
            PaymentDocumentSourceQuery payments, ReceivablePaymentAccess receivables,
            BusinessEvidenceQuery businessEvidence) {
        this.sales = sales;
        this.catalog = catalog;
        this.customerAccounts = customerAccounts;
        this.organization = organization;
        this.fulfillment = fulfillment;
        this.payments = payments;
        this.receivables = receivables;
        this.businessEvidence = businessEvidence;
    }

    @Override
    public BusinessDocumentProjections.DocumentProjection lookup(String tenantId, String workspaceId,
            DocumentSubjectReference subject, BusinessDocumentType documentType) {
        UUID tenant = uuid(tenantId, "tenantId");
        UUID workspace = uuid(workspaceId, "workspaceId");
        UUID id = uuid(subject.subjectId(), "subjectId");
        return switch (subject.type()) {
            case SALES_ORDER -> salesOrderProjection(tenant, workspace, id, documentType);
            case PURCHASE_REQUEST -> purchaseRequestProjection(tenant, workspace, id);
            case DISPATCH_ORDER -> deliveryGuideProjection(tenant, workspace, id);
            case PROOF_OF_DELIVERY -> podProjection(tenant, workspace, id);
            case DELIVERY_INCIDENT -> incidentProjection(tenant, workspace, id);
            case PAYMENT -> paymentProjection(tenant, workspace, id);
            case RECEIVABLE -> receivableProjection(tenant, workspace, id);
            case INBOUND_RECEIVING_DISCREPANCY -> throw unsupported(documentType, subject.type());
            case WAREHOUSE -> throw unsupported(documentType, subject.type());
        };
    }

    private BusinessDocumentProjections.DocumentProjection salesOrderProjection(UUID tenant, UUID workspace,
            UUID id, BusinessDocumentType type) {
        OrderData order = loadOrder(tenant, workspace, id);
        return switch (type) {
            case ORDER_SUMMARY -> new BusinessDocumentProjections.OrderSummaryProjection(
                    id.toString(), order.issuer(), order.buyer(), order.number(), order.createdAt(), order.status(),
                    order.lines(), order.totals(), order.delivery(), order.paymentTerms(), order.notes());
            case COMMERCIAL_INVOICE_DRAFT -> new BusinessDocumentProjections.CommercialInvoiceDraftProjection(
                    id.toString(), order.issuer(), order.buyer(), "DRAFT-" + order.number(), order.number(),
                    order.createdAt(), order.status(), order.lines(), order.totals(), order.delivery(),
                    order.paymentTerms(), joinNotes("FISCAL_DRAFT", order.notes()));
            default -> throw unsupported(type, DocumentSubjectType.SALES_ORDER);
        };
    }

    private BusinessDocumentProjections.DocumentProjection purchaseRequestProjection(UUID tenant, UUID workspace,
            UUID id) {
        SalesDocumentSourceQuery.PurchaseRequestSnapshot request = sales.findPurchaseRequest(tenant, workspace, id)
                .orElseThrow(() -> notFound("Purchase request"));
        PartyData party = loadParty(tenant, workspace, request.customerAccountId(), "Purchase request", false, true);
        List<DocumentLine> lines = documentLines(tenant, workspace, request.lines());
        String currency = lines.isEmpty()
                ? organizationSnapshot(tenant, workspace, "Purchase request").regionalCurrency()
                : lines.get(0).currency();
        BigDecimal subtotal = lines.stream().map(DocumentLine::lineTotal).reduce(ZERO, BigDecimal::add);
        Instant deliveryAt = request.requestedDeliveryDate() == null ? null
                : request.requestedDeliveryDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        return new BusinessDocumentProjections.PurchaseRequestSummaryProjection(
                id.toString(), party.issuer(), party.buyer(), request.code(), request.createdAt(),
                request.requestedDeliveryDate() == null ? null : request.requestedDeliveryDate().toString(),
                request.status(), lines, new DocumentTotals(subtotal, ZERO, subtotal, currency),
                new DeliveryInfo(request.deliveryAddressSnapshot(), null, request.routeSnapshot(), null, null, null,
                        deliveryAt), request.paymentOption(), request.comments(), request.reviewNote());
    }

    private BusinessDocumentProjections.DocumentProjection deliveryGuideProjection(UUID tenant, UUID workspace,
            UUID id) {
        DispatchData dispatch = loadDispatch(tenant, workspace, id);
        return new BusinessDocumentProjections.DeliveryGuideDraftProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(),
                dispatch.order().number(), dispatch.issueDate(), dispatch.status(), dispatch.order().lines(),
                dispatch.order().totals(), dispatch.delivery(), dispatch.order().paymentTerms(),
                "NON_FISCAL_DRAFT - Delivery guide information only");
    }

    private BusinessDocumentProjections.DocumentProjection podProjection(UUID tenant, UUID workspace, UUID id) {
        FulfillmentDocumentSourceQuery.ProofOfDelivery pod = fulfillment.findPod(tenant, workspace, id)
                .orElseThrow(() -> notFound("Proof of delivery"));
        DispatchData dispatch = loadDispatch(tenant, workspace, pod.dispatchId());
        String evidence = String.valueOf(businessEvidence.countAvailableForSubject(
                tenant, workspace, "PROOF_OF_DELIVERY", id));
        return new BusinessDocumentProjections.PodReportProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(),
                dispatch.order().number(), pod.completedAt(), pod.status(), pod.receiverName(),
                dispatch.temperatureStatus(), dispatch.temperatureSummary(), dispatch.order().lines(),
                dispatch.order().totals(), dispatch.delivery(), dispatch.order().paymentTerms(), pod.notes(), evidence);
    }

    private BusinessDocumentProjections.DocumentProjection incidentProjection(UUID tenant, UUID workspace, UUID id) {
        FulfillmentDocumentSourceQuery.Incident incident = fulfillment.findIncident(tenant, workspace, id)
                .orElseThrow(() -> notFound("Delivery incident"));
        DispatchData dispatch = loadDispatch(tenant, workspace, incident.dispatchId());
        String evidence = String.valueOf(businessEvidence.countAvailableForSubject(
                tenant, workspace, "DELIVERY_INCIDENT", id));
        return new BusinessDocumentProjections.IncidentReportProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(),
                dispatch.order().number(), incident.occurredAt(),
                incident.resolution() == null ? "OPEN" : "RESOLVED", incident.incidentType(),
                incident.severity(), incident.description(), dispatch.temperatureSummary(),
                dispatch.order().lines(), dispatch.order().totals(), dispatch.delivery(),
                dispatch.order().paymentTerms(), null, incident.resolution(), evidence);
    }

    private BusinessDocumentProjections.DocumentProjection paymentProjection(UUID tenant, UUID workspace, UUID id) {
        PaymentDocumentSourceQuery.Snapshot payment = payments.find(tenant, workspace, id)
                .orElseThrow(() -> notFound("Payment"));
        ReceivablePaymentAccess.Snapshot receivable = payment.receivableId() == null ? null
                : receivables.find(tenant, workspace, payment.receivableId()).orElse(null);
        if (receivable == null) throw notFound("Payment");
        PartyData party = loadParty(tenant, workspace, payment.customerAccountId(), "Client account", true, false);
        OrderData order = "SALES_ORDER".equals(receivable.subjectType())
                && receivable.subjectId() != null
                ? loadOrder(tenant, workspace, receivable.subjectId()) : null;
        List<DocumentLine> lines = order == null ? List.of() : order.lines();
        DocumentTotals totals = new DocumentTotals(payment.amount(), ZERO, payment.amount(), payment.currency());
        return new BusinessDocumentProjections.PaymentReceiptProjection(
                id.toString(), party.issuer(), party.buyer(), "PAY-" + id, receivable.number(),
                order == null ? null : order.number(), payment.createdAt(), payment.status(), payment.method(),
                payment.amount(), receivables.allocatedAmount(tenant, workspace, id), payment.providerReference(),
                lines, totals, DeliveryInfo.empty(), null, "Payment receipt");
    }

    private BusinessDocumentProjections.DocumentProjection receivableProjection(UUID tenant, UUID workspace, UUID id) {
        ReceivablePaymentAccess.Snapshot receivable = receivables.find(tenant, workspace, id)
                .orElseThrow(() -> notFound("Receivable"));
        if (receivable.amountPaid().signum() <= 0) {
            throw new IllegalArgumentException("Receivable has no paid amount for a receipt");
        }
        PartyData party = loadParty(tenant, workspace, receivable.clientAccountId(), "Client account", true, false);
        OrderData order = "SALES_ORDER".equals(receivable.subjectType())
                && receivable.subjectId() != null
                ? loadOrder(tenant, workspace, receivable.subjectId()) : null;
        List<DocumentLine> lines = order == null ? List.of() : order.lines();
        DocumentTotals totals = new DocumentTotals(receivable.amountPaid(), ZERO, receivable.amountPaid(),
                receivable.currency());
        return new BusinessDocumentProjections.PaymentReceiptProjection(
                id.toString(), party.issuer(), party.buyer(), "REC-" + receivable.number(), receivable.number(),
                order == null ? null : order.number(), receivable.createdAt(), receivable.status(),
                "RECEIVABLE_SETTLEMENT", receivable.amountPaid(), receivable.amountPaid(), null, lines, totals,
                DeliveryInfo.empty(), null, "Receipt generated from settled receivable");
    }

    private OrderData loadOrder(UUID tenant, UUID workspace, UUID id) {
        SalesDocumentSourceQuery.SalesOrderSnapshot order = sales.findOrder(tenant, workspace, id)
                .orElseThrow(() -> notFound("Sales order"));
        PartyData party = loadParty(tenant, workspace, order.customerAccountId(), "Sales order", true, false);
        List<DocumentLine> lines = documentLines(tenant, workspace, order.lines());
        BigDecimal subtotal = lines.stream().map(DocumentLine::lineTotal).reduce(ZERO, BigDecimal::add);
        Instant deliveryAt = order.requestedDeliveryDate() == null ? null
                : order.requestedDeliveryDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        return new OrderData(order.number(), order.createdAt(), order.status(), party.issuer(), party.buyer(), lines,
                totals(subtotal, order.totalAmount(), order.currency()),
                new DeliveryInfo(first(order.deliveryAddressSnapshot(), order.deliverySnapshot()),
                        order.warehouseSelectionSnapshot(), order.routeSnapshot(), null, null, null, deliveryAt),
                order.paymentOption(), order.notes(), id);
    }

    private DispatchData loadDispatch(UUID tenant, UUID workspace, UUID id) {
        FulfillmentDocumentSourceQuery.Dispatch dispatch = fulfillment.findDispatch(tenant, workspace, id)
                .orElseThrow(() -> notFound("Dispatch order"));
        OrderData order = loadOrder(tenant, workspace, dispatch.salesOrderId());
        Instant deliveryAt = dispatch.deliveryWindowStart() == null ? dispatch.eta() : dispatch.deliveryWindowStart();
        DeliveryInfo delivery = new DeliveryInfo(dispatch.destinationSnapshot(), order.delivery().warehouse(),
                dispatch.routeName(), dispatch.dispatchNumber(), dispatch.responsibleDisplayNameSnapshot(),
                dispatch.vehicleReference(), deliveryAt);
        return new DispatchData(dispatch.dispatchNumber(), dispatch.status(), deliveryAt, order, delivery,
                dispatch.temperatureStatus(), dispatch.temperatureSummary());
    }

    private List<DocumentLine> documentLines(UUID tenant, UUID workspace,
            List<SalesDocumentSourceQuery.Line> sourceLines) {
        List<UUID> skuIds = sourceLines.stream().map(SalesDocumentSourceQuery.Line::skuId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, CatalogDocumentSourceQuery.Sku> skus = catalog.skus(tenant, workspace, skuIds);
        List<UUID> familyIds = new ArrayList<>();
        for (SalesDocumentSourceQuery.Line line : sourceLines) {
            if (line.familyId() != null) familyIds.add(line.familyId());
            CatalogDocumentSourceQuery.Sku sku = line.skuId() == null ? null : skus.get(line.skuId());
            if (sku != null && sku.familyId() != null) familyIds.add(sku.familyId());
        }
        Map<UUID, String> familyNames = catalog.familyNames(tenant, workspace,
                familyIds.stream().distinct().toList());
        return sourceLines.stream().map(line -> {
            CatalogDocumentSourceQuery.Sku sku = line.skuId() == null ? null : skus.get(line.skuId());
            UUID familyId = line.familyId() != null ? line.familyId() : sku == null ? null : sku.familyId();
            String skuCode = coalesce(line.skuCodeSnapshot(), sku == null ? null : sku.skuCode(), line.catalogItemId());
            String familyName = coalesce(familyId == null ? null : familyNames.get(familyId),
                    line.familyCodeSnapshot(), line.itemNameSnapshot());
            BigDecimal unitPrice = line.unitPrice();
            return new DocumentLine(skuCode, familyName, line.presentation(), line.quantity(), line.unit(),
                    unitPrice, ZERO, unitPrice, line.lineSubtotal(), line.currency(),
                    sku == null ? null : sku.grossWeight());
        }).toList();
    }

    private PartyData loadParty(UUID tenant, UUID workspace, UUID customerAccountId,
            String missingOwner, boolean includeBuyerCode, boolean blankLegalFallback) {
        CustomerAccountDetails account = customerAccount(tenant, workspace, customerAccountId, missingOwner);
        OrganizationDocumentSourceQuery.Snapshot issuer = organizationSnapshot(tenant, workspace, missingOwner);
        String issuerName = blankLegalFallback ? first(issuer.legalName(), issuer.tenantName())
                : coalesce(issuer.legalName(), issuer.tenantName());
        return new PartyData(issuer(issuer, issuerName), buyer(account, includeBuyerCode));
    }

    private CustomerAccountDetails customerAccount(UUID tenant, UUID workspace, UUID customerAccountId,
            String missingOwner) {
        if (customerAccountId == null) throw notFound(missingOwner);
        return customerAccounts.findHistoricalDetails(tenant.toString(), workspace.toString(), customerAccountId.toString())
                .orElseThrow(() -> notFound(missingOwner));
    }

    private OrganizationDocumentSourceQuery.Snapshot organizationSnapshot(UUID tenant, UUID workspace,
            String missingOwner) {
        return organization.find(tenant, workspace).orElseThrow(() -> notFound(missingOwner));
    }

    private static BusinessParty issuer(OrganizationDocumentSourceQuery.Snapshot source, String name) {
        return new BusinessParty(name, source.businessIdentifier(), "BUSINESS_ID", source.businessIdentifier(), null);
    }

    private static BusinessParty buyer(CustomerAccountDetails account, boolean includeCode) {
        return new BusinessParty(account.businessName(), includeCode ? account.code() : null,
                account.taxIdentifierType(), account.taxIdentifierValue(), null);
    }

    private static DocumentTotals totals(BigDecimal subtotal, BigDecimal total, String currency) {
        BigDecimal tax = total.subtract(subtotal).max(ZERO);
        return new DocumentTotals(subtotal, tax, total, currency);
    }

    private static String joinNotes(String prefix, String notes) {
        return notes == null || notes.isBlank() ? prefix : prefix + " - " + notes;
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String coalesce(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    private static UUID uuid(String value, String label) {
        try { return UUID.fromString(value); } catch (RuntimeException e) {
            throw new IllegalArgumentException(label + " is invalid", e);
        }
    }

    private static IllegalArgumentException notFound(String value) {
        return new IllegalArgumentException(value + " not found");
    }

    private static IllegalArgumentException unsupported(BusinessDocumentType type, DocumentSubjectType subject) {
        return new IllegalArgumentException("Document type " + type + " is not supported for " + subject);
    }

    private record OrderData(String number, Instant createdAt, String status, BusinessParty issuer, BusinessParty buyer,
            List<DocumentLine> lines, DocumentTotals totals, DeliveryInfo delivery, String paymentTerms,
            String notes, UUID id) { }

    private record DispatchData(String number, String status, Instant issueDate, OrderData order,
            DeliveryInfo delivery, String temperatureStatus, String temperatureSummary) { }

    private record PartyData(BusinessParty issuer, BusinessParty buyer) { }
}
