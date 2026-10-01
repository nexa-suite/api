package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.DocumentSubjectLookupPort;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectSnapshot;
import com.nexa.api.businessdocuments.domain.publicapi.DocumentSubjectType;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Read-only subject resolver composed from subject owners' public query contracts. */
@Repository
@Profile("!test")
public class DocumentSubjectCompositionAdapter implements DocumentSubjectLookupPort {
    private final SalesDocumentSourceQuery sales;
    private final ReceivablePaymentAccess receivables;
    private final PaymentDocumentSourceQuery payments;
    private final FulfillmentDocumentSourceQuery fulfillment;
    private final InboundReceivingDiscrepancySubjectQuery inboundDiscrepancies;

    public DocumentSubjectCompositionAdapter(SalesDocumentSourceQuery sales,
            ReceivablePaymentAccess receivables, PaymentDocumentSourceQuery payments,
            FulfillmentDocumentSourceQuery fulfillment,
            InboundReceivingDiscrepancySubjectQuery inboundDiscrepancies) {
        this.sales = sales;
        this.receivables = receivables;
        this.payments = payments;
        this.fulfillment = fulfillment;
        this.inboundDiscrepancies = inboundDiscrepancies;
    }

    @Override
    public DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, String actorMembershipId,
                                          DocumentSubjectReference subject) {
        UUID tenant = uuid(tenantId, "tenantId");
        UUID workspace = uuid(workspaceId, "workspaceId");
        UUID actor = uuid(actorMembershipId, "actorMembershipId");
        UUID id = uuid(subject.subjectId(), "subjectId");
        return switch (subject.type()) {
            case SALES_ORDER -> resolveSalesOrder(tenant, workspace, id, subject);
            case PURCHASE_REQUEST -> resolvePurchaseRequest(tenant, workspace, id, subject);
            case RECEIVABLE -> resolveReceivable(tenant, workspace, id, subject);
            case PAYMENT -> resolvePayment(tenant, workspace, id, subject);
            case DISPATCH_ORDER -> resolveDispatchOrder(tenant, workspace, id, subject);
            case PROOF_OF_DELIVERY -> resolveProofOfDelivery(tenant, workspace, id, subject);
            case DELIVERY_INCIDENT -> resolveDeliveryIncident(tenant, workspace, id, subject);
            case INBOUND_RECEIVING_DISCREPANCY -> resolveInboundDiscrepancy(tenant, workspace, actor, id, subject);
        };
    }

    private DocumentSubjectSnapshot resolveInboundDiscrepancy(UUID tenant, UUID workspace, UUID actor, UUID id,
                                                               DocumentSubjectReference subject) {
        return inboundDiscrepancies.find(tenant, workspace, actor, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(), null,
                        value.lifecycleState(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolvePurchaseRequest(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return sales.findPurchaseRequest(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        value.customerAccountId().toString(), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }
    private DocumentSubjectSnapshot resolveReceivable(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return receivables.find(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        value.clientAccountId().toString(), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }
    private DocumentSubjectSnapshot resolvePayment(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return payments.find(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        nullable(value.customerAccountId()), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveSalesOrder(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return sales.findOrder(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        value.customerAccountId().toString(), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveDispatchOrder(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return fulfillment.findDispatch(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        nullable(value.customerAccountId()), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveProofOfDelivery(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return fulfillment.findPod(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        nullable(value.customerAccountId()), value.status(), true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveDeliveryIncident(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        var incident = fulfillment.findIncidentSubject(tenant, workspace, id);
        if (incident.isPresent()) {
            var value = incident.get();
            if (value.customerAccountId() != null) {
                return snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        value.customerAccountId().toString(), value.status(), true);
            }
            if (value.salesOrderId() == null) return absent(tenant, workspace, subject);
            return sales.findOrder(tenant, workspace, value.salesOrderId())
                    .map(order -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                            order.customerAccountId().toString(), value.status(), true))
                    .orElseGet(() -> absent(tenant, workspace, subject));
        }
        return fulfillment.findIncident(tenant, workspace, id)
                .map(value -> snapshot(tenant, workspace, subject.type(), value.id().toString(),
                        nullable(value.customerAccountId()), "RECORDED", true))
                .orElseGet(() -> absent(tenant, workspace, subject));
    }

    private static DocumentSubjectSnapshot snapshot(UUID tenant, UUID workspace, DocumentSubjectType type, String id, String clientAccountId, String state, boolean exists) {
        return new DocumentSubjectSnapshot(tenant.toString(), workspace.toString(), type, id, clientAccountId, state, exists);
    }

    private static DocumentSubjectSnapshot absent(UUID tenant, UUID workspace, DocumentSubjectReference subject) {
        return snapshot(tenant, workspace, subject.type(), subject.subjectId(), null, "NOT_FOUND", false);
    }

    private static String nullable(Object value) { return value == null ? null : value.toString(); }

    private static UUID uuid(String value, String label) {
        try { return UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(label + " is invalid", e); }
    }
}
