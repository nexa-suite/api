package com.nexa.api.businessdocuments.infrastructure.subject;

import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.BusinessParty;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DeliveryInfo;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentLine;
import com.nexa.api.businessdocuments.application.model.BusinessDocumentProjections.DocumentTotals;
import com.nexa.api.businessdocuments.application.port.DocumentProjectionLookupPort;
import com.nexa.api.businessdocuments.domain.model.businessdocument.BusinessDocumentType;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectType;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only ACL that composes immutable document projections from the owning
 * bounded contexts. It has no document or storage writes.
 */
@Repository
@Profile("!test")
public class JdbcDocumentProjectionLookupAdapter implements DocumentProjectionLookupPort {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final JdbcTemplate jdbc;

    public JdbcDocumentProjectionLookupAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        };
    }

    private BusinessDocumentProjections.DocumentProjection salesOrderProjection(UUID tenant, UUID workspace, UUID id,
            BusinessDocumentType type) {
        OrderData order = loadOrder(tenant, workspace, id);
        return switch (type) {
            case ORDER_SUMMARY -> new BusinessDocumentProjections.OrderSummaryProjection(
                    id.toString(), order.issuer(), order.buyer(), order.number(), order.createdAt(), order.status(),
                    order.lines(), order.totals(), order.delivery(), order.paymentTerms(), order.notes());
            case COMMERCIAL_INVOICE_DRAFT -> new BusinessDocumentProjections.CommercialInvoiceDraftProjection(
                    id.toString(), order.issuer(), order.buyer(), "DRAFT-" + order.number(), order.number(),
                    order.createdAt(), order.status(), order.lines(), order.totals(), order.delivery(), order.paymentTerms(),
                    joinNotes("FISCAL_DRAFT", order.notes()));
            default -> throw unsupported(type, DocumentSubjectType.SALES_ORDER);
        };
    }

    private BusinessDocumentProjections.DocumentProjection purchaseRequestProjection(UUID tenant, UUID workspace, UUID id) {
        PurchaseRequestData request = jdbc.query(
                "select r.id,r.code,r.created_at,r.requested_delivery_date,r.status,r.payment_option,r.comments,r.review_note,r.delivery_address_snapshot::text address_snapshot,r.route_snapshot::text route_snapshot,r.warehouse_selection_snapshot::text warehouse_snapshot,"
                        + "c.business_name,c.tax_identifier_type,c.tax_identifier_value,t.name issuer_name,os.legal_name,os.business_identifier,"
                        + "coalesce(rs.currency,'PEN') currency from sales.purchase_request r "
                        + "join sales.client_account c on c.tenant_id=r.tenant_id and c.workspace_id=r.workspace_id and c.id=r.client_account_id "
                        + "join tenant_management.tenant t on t.id=r.tenant_id left join tenant_management.organization_settings os on os.tenant_id=r.tenant_id "
                        + "left join tenant_management.regional_settings rs on rs.tenant_id=r.tenant_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=?",
                (rs, row) -> purchaseRequestData(rs, tenant, workspace, id), tenant, workspace, id)
                .stream().findFirst().orElseThrow(() -> notFound("Purchase request"));
        return new BusinessDocumentProjections.PurchaseRequestSummaryProjection(
                id.toString(), request.issuer(), request.buyer(), request.code(), request.createdAt(), request.requestedDeliveryDate(),
                request.status(), request.lines(), request.totals(), new DeliveryInfo(request.address(), null, request.route(), null, null, null,
                        request.deliveryAt()), request.paymentTerms(), request.comments(), request.reviewNote());
    }

    private BusinessDocumentProjections.DocumentProjection deliveryGuideProjection(UUID tenant, UUID workspace, UUID id) {
        DispatchData dispatch = loadDispatch(tenant, workspace, id);
        return new BusinessDocumentProjections.DeliveryGuideDraftProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(), dispatch.order().number(),
                dispatch.issueDate(), dispatch.status(), dispatch.order().lines(), dispatch.order().totals(), dispatch.delivery(),
                dispatch.order().paymentTerms(), "NON_FISCAL_DRAFT - Delivery guide information only");
    }

    private BusinessDocumentProjections.DocumentProjection podProjection(UUID tenant, UUID workspace, UUID id) {
        PodData pod = jdbc.query(
                "select p.id,p.receiver_name,p.completed_at,p.notes,p.status,p.photo_evidence_declared,p.signature_evidence_declared,d.id dispatch_id "
                        + "from logistics.proof_of_delivery p join logistics.dispatch_order d on d.tenant_id=p.tenant_id and d.workspace_id=p.workspace_id and d.id=p.dispatch_order_id "
                        + "where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> new PodData(rs.getString("receiver_name"), rs.getTimestamp("completed_at").toInstant(), rs.getString("notes"),
                        rs.getString("status"), rs.getBoolean("photo_evidence_declared"), rs.getBoolean("signature_evidence_declared"),
                        rs.getObject("dispatch_id", UUID.class)), tenant, workspace, id)
                .stream().findFirst().orElseThrow(() -> notFound("Proof of delivery"));
        DispatchData dispatch = loadDispatch(tenant, workspace, pod.dispatchId());
        String evidence = evidenceCount(tenant, workspace, "PROOF_OF_DELIVERY", id);
        return new BusinessDocumentProjections.PodReportProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(), dispatch.order().number(),
                pod.completedAt(), pod.status(), pod.receiver(), dispatch.temperatureStatus(), dispatch.temperatureSummary(),
                dispatch.order().lines(), dispatch.order().totals(), dispatch.delivery(), dispatch.order().paymentTerms(),
                pod.notes(), evidence);
    }

    private BusinessDocumentProjections.DocumentProjection incidentProjection(UUID tenant, UUID workspace, UUID id) {
        IncidentData incident = jdbc.query(
                "select i.id,i.incident_type,i.severity,i.description,i.occurred_at,i.resolution,d.id dispatch_id "
                        + "from logistics.delivery_incident i join logistics.dispatch_order d on d.tenant_id=i.tenant_id and d.workspace_id=i.workspace_id and d.id=i.dispatch_order_id "
                        + "where i.tenant_id=? and i.workspace_id=? and i.id=?",
                (rs, row) -> new IncidentData(rs.getString("incident_type"), rs.getString("severity"), rs.getString("description"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("resolution"), rs.getObject("dispatch_id", UUID.class)),
                tenant, workspace, id).stream().findFirst().orElseThrow(() -> notFound("Delivery incident"));
        DispatchData dispatch = loadDispatch(tenant, workspace, incident.dispatchId());
        String evidence = evidenceCount(tenant, workspace, "DELIVERY_INCIDENT", id);
        return new BusinessDocumentProjections.IncidentReportProjection(
                id.toString(), dispatch.order().issuer(), dispatch.order().buyer(), dispatch.number(), dispatch.order().number(),
                incident.occurredAt(), incident.resolution() == null ? "OPEN" : "RESOLVED", incident.type(), incident.severity(),
                incident.description(), dispatch.temperatureSummary(), dispatch.order().lines(), dispatch.order().totals(), dispatch.delivery(),
                dispatch.order().paymentTerms(), null, incident.resolution(), evidence);
    }

    private BusinessDocumentProjections.DocumentProjection paymentProjection(UUID tenant, UUID workspace, UUID id) {
        PaymentData payment = jdbc.query(
                "select p.id,p.amount,p.currency,p.method,p.status,p.provider_payment_intent_id,p.created_at,p.receivable_id,r.receivable_number,r.amount receivable_amount,r.amount_paid,r.subject_id,r.subject_type,so.number order_number "
                        + "from payments.payment p join payments.receivable r on r.tenant_id=p.tenant_id and r.workspace_id=p.workspace_id and r.id=p.receivable_id "
                        + "left join sales.sales_order so on so.tenant_id=r.tenant_id and so.workspace_id=r.workspace_id and r.subject_type='SALES_ORDER' and so.id=r.subject_id "
                        + "where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> paymentData(rs, tenant, workspace, id), tenant, workspace, id)
                .stream().findFirst().orElseThrow(() -> notFound("Payment"));
        PartyData party = loadParty(tenant, workspace, payment.clientAccountId());
        List<DocumentLine> lines = payment.orderId() == null ? List.of() : loadOrder(tenant, workspace, payment.orderId()).lines();
        String currency = payment.currency();
        DocumentTotals totals = new DocumentTotals(payment.amount(), ZERO, payment.amount(), currency);
        return new BusinessDocumentProjections.PaymentReceiptProjection(
                id.toString(), party.issuer(), party.buyer(), "PAY-" + id, payment.receivableNumber(), payment.orderNumber(),
                payment.createdAt(), payment.status(), payment.method(), payment.amount(), allocation(tenant, workspace, id),
                payment.providerReference(), lines, totals, DeliveryInfo.empty(), null, "Payment receipt");
    }

    private BusinessDocumentProjections.DocumentProjection receivableProjection(UUID tenant, UUID workspace, UUID id) {
        ReceivableData receivable = jdbc.query(
                "select r.id,r.receivable_number,r.amount,r.amount_paid,r.currency,r.status,r.created_at,r.client_account_id,r.subject_type,r.subject_id,so.number order_number "
                        + "from payments.receivable r left join sales.sales_order so on so.tenant_id=r.tenant_id and so.workspace_id=r.workspace_id and r.subject_type='SALES_ORDER' and so.id=r.subject_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=?",
                (rs, row) -> new ReceivableData(rs.getObject("id", UUID.class), rs.getString("receivable_number"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("amount_paid"), rs.getString("currency"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("client_account_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("order_number")),
                tenant, workspace, id).stream().findFirst().orElseThrow(() -> notFound("Receivable"));
        if (receivable.amountPaid().signum() <= 0) throw new IllegalArgumentException("Receivable has no paid amount for a receipt");
        PartyData party = loadParty(tenant, workspace, receivable.clientAccountId());
        List<DocumentLine> lines = "SALES_ORDER".equals(receivable.subjectType()) && receivable.subjectId() != null
                ? loadOrder(tenant, workspace, receivable.subjectId()).lines() : List.of();
        DocumentTotals totals = new DocumentTotals(receivable.amountPaid(), ZERO, receivable.amountPaid(), receivable.currency());
        return new BusinessDocumentProjections.PaymentReceiptProjection(
                id.toString(), party.issuer(), party.buyer(), "REC-" + receivable.receivableNumber(), receivable.receivableNumber(),
                receivable.orderNumber(), receivable.createdAt(), receivable.status(), "RECEIVABLE_SETTLEMENT", receivable.amountPaid(),
                receivable.amountPaid(), null, lines, totals, DeliveryInfo.empty(), null, "Receipt generated from settled receivable");
    }

    private OrderData loadOrder(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query(
                "select so.id,so.number,so.created_at,so.status,so.requested_delivery_date,so.delivery_snapshot,so.delivery_address_snapshot::text address_snapshot,so.route_snapshot::text route_snapshot,so.warehouse_selection_snapshot::text warehouse_snapshot,so.payment_option,so.notes,so.currency,so.total_amount,"
                        + "c.business_name,c.tax_identifier_type,c.tax_identifier_value,c.code client_code,c.delivery_profile,"
                        + "coalesce(os.legal_name,t.name) issuer_name,os.business_identifier "
                        + "from sales.sales_order so join sales.client_account c on c.tenant_id=so.tenant_id and c.workspace_id=so.workspace_id and c.id=so.client_account_id "
                        + "join tenant_management.tenant t on t.id=so.tenant_id left join tenant_management.organization_settings os on os.tenant_id=so.tenant_id "
                        + "where so.tenant_id=? and so.workspace_id=? and so.id=?",
                (rs, row) -> orderData(rs, tenant, workspace, id), tenant, workspace, id)
                .stream().findFirst().orElseThrow(() -> notFound("Sales order"));
    }

    private OrderData orderData(ResultSet rs, UUID tenant, UUID workspace, UUID id) throws SQLException {
        Date requestedDate = rs.getDate("requested_delivery_date");
        Instant deliveryAt = requestedDate == null ? null : requestedDate.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        BusinessParty issuer = new BusinessParty(rs.getString("issuer_name"), rs.getString("business_identifier"), "BUSINESS_ID", rs.getString("business_identifier"), null);
        BusinessParty buyer = new BusinessParty(rs.getString("business_name"), rs.getString("client_code"), rs.getString("tax_identifier_type"), rs.getString("tax_identifier_value"), null);
        List<DocumentLine> lines = orderLines(tenant, workspace, id);
        BigDecimal subtotal = lines.stream().map(DocumentLine::lineTotal).reduce(ZERO, BigDecimal::add);
        BigDecimal total = rs.getBigDecimal("total_amount");
        return new OrderData(rs.getString("number"), rs.getTimestamp("created_at").toInstant(), rs.getString("status"), issuer, buyer,
                lines, totals(subtotal, total, rs.getString("currency")),
                new DeliveryInfo(first(rs.getString("address_snapshot"), rs.getString("delivery_snapshot")), rs.getString("warehouse_snapshot"),
                        rs.getString("route_snapshot"), null, null, null, deliveryAt), rs.getString("payment_option"), rs.getString("notes"), id);
    }

    private PurchaseRequestData purchaseRequestData(ResultSet rs, UUID tenant, UUID workspace, UUID id) throws SQLException {
        Date requestedDate = rs.getDate("requested_delivery_date");
        Instant deliveryAt = requestedDate == null ? null : requestedDate.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        BusinessParty issuer = new BusinessParty(first(rs.getString("legal_name"), rs.getString("issuer_name")), rs.getString("business_identifier"),
                "BUSINESS_ID", rs.getString("business_identifier"), null);
        BusinessParty buyer = new BusinessParty(rs.getString("business_name"), null, rs.getString("tax_identifier_type"), rs.getString("tax_identifier_value"), null);
        List<DocumentLine> lines = purchaseRequestLines(tenant, workspace, id);
        String currency = lines.isEmpty() ? rs.getString("currency") : lines.get(0).currency();
        BigDecimal subtotal = lines.stream().map(DocumentLine::lineTotal).reduce(ZERO, BigDecimal::add);
        return new PurchaseRequestData(rs.getString("code"), rs.getTimestamp("created_at").toInstant(), requestedDate == null ? null : requestedDate.toString(),
                deliveryAt, rs.getString("status"), issuer, buyer, lines, new DocumentTotals(subtotal, ZERO, subtotal, currency),
                rs.getString("address_snapshot"), rs.getString("route_snapshot"), rs.getString("payment_option"),
                rs.getString("comments"), rs.getString("review_note"));
    }

    private DispatchData loadDispatch(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query(
                "select d.id,d.dispatch_number,d.status,d.destination_snapshot,d.delivery_window_start,d.eta,d.responsible_display_name_snapshot,d.vehicle_reference,d.route_name,d.temperature_status,d.sales_order_id "
                        + "from logistics.dispatch_order d where d.tenant_id=? and d.workspace_id=? and d.id=?",
                (rs, row) -> dispatchData(rs, tenant, workspace), tenant, workspace, id)
                .stream().findFirst().orElseThrow(() -> notFound("Dispatch order"));
    }

    private DispatchData dispatchData(ResultSet rs, UUID tenant, UUID workspace) throws SQLException {
        UUID salesOrderId = rs.getObject("sales_order_id", UUID.class);
        OrderData order = loadOrder(tenant, workspace, salesOrderId);
        Instant deliveryAt = rs.getTimestamp("delivery_window_start") == null
                ? rs.getTimestamp("eta") == null ? null : rs.getTimestamp("eta").toInstant()
                : rs.getTimestamp("delivery_window_start").toInstant();
        DeliveryInfo delivery = new DeliveryInfo(rs.getString("destination_snapshot"), order.delivery().warehouse(), rs.getString("route_name"),
                rs.getString("dispatch_number"), rs.getString("responsible_display_name_snapshot"), rs.getString("vehicle_reference"), deliveryAt);
        String temperatureStatus = rs.getString("temperature_status");
        String temperatureSummary = jdbc.queryForObject(
                "select coalesce(string_agg(coalesce(value::text,'n/a') || ' ' || unit, ', ' order by recorded_at desc), 'No temperature readings') from logistics.temperature_reading where tenant_id=? and workspace_id=? and dispatch_order_id=?",
                String.class, tenant, workspace, rs.getObject("id", UUID.class));
        return new DispatchData(rs.getString("dispatch_number"), rs.getString("status"), deliveryAt, order, delivery, temperatureStatus, temperatureSummary);
    }

    private List<DocumentLine> orderLines(UUID tenant, UUID workspace, UUID orderId) {
        return jdbc.query(
                "select coalesce(l.sku_code_snapshot,s.sku_code,l.catalog_item_id) sku_code,coalesce(f.name,l.product_family_code_snapshot,l.item_name_snapshot) product_family,l.presentation_snapshot,l.quantity,l.unit,l.unit_price_amount,l.line_subtotal,l.unit_price_currency,s.gross_weight "
                        + "from sales.sales_order_line l left join catalog_management.sellable_sku s on s.id=l.sku_id and s.tenant_id=? and s.workspace_id=? "
                        + "left join catalog_management.product_family f on f.id=coalesce(l.product_family_id,s.family_id) and f.tenant_id=? and f.workspace_id=? "
                        + "where l.sales_order_id=? order by l.created_at,l.id",
                (rs, row) -> line(rs), tenant, workspace, tenant, workspace, orderId);
    }

    private List<DocumentLine> purchaseRequestLines(UUID tenant, UUID workspace, UUID requestId) {
        return jdbc.query(
                "select coalesce(l.sku_code_snapshot,s.sku_code,l.catalog_item_id) sku_code,coalesce(f.name,l.product_family_code_snapshot,l.item_name_snapshot) product_family,l.presentation_snapshot,l.quantity,l.unit,l.unit_price_amount,l.unit_price_currency,(l.quantity*l.unit_price_amount) line_subtotal,s.gross_weight "
                        + "from sales.purchase_request_line l left join catalog_management.sellable_sku s on s.id=l.sku_id and s.tenant_id=? and s.workspace_id=? "
                        + "left join catalog_management.product_family f on f.id=coalesce(l.product_family_id,s.family_id) and f.tenant_id=? and f.workspace_id=? "
                        + "where l.purchase_request_id=? order by l.created_at,l.id",
                (rs, row) -> line(rs), tenant, workspace, tenant, workspace, requestId);
    }

    private DocumentLine line(ResultSet rs) throws SQLException {
        BigDecimal unitPrice = rs.getBigDecimal("unit_price_amount");
        return new DocumentLine(rs.getString("sku_code"), rs.getString("product_family"), rs.getString("presentation_snapshot"),
                rs.getBigDecimal("quantity"), rs.getString("unit"), unitPrice, ZERO, unitPrice,
                rs.getBigDecimal("line_subtotal"), rs.getString("unit_price_currency"), rs.getBigDecimal("gross_weight"));
    }

    private PartyData loadParty(UUID tenant, UUID workspace, UUID clientAccountId) {
        return jdbc.query(
                "select c.business_name,c.code,c.tax_identifier_type,c.tax_identifier_value,coalesce(os.legal_name,t.name) issuer_name,os.business_identifier "
                        + "from sales.client_account c join tenant_management.tenant t on t.id=c.tenant_id left join tenant_management.organization_settings os on os.tenant_id=c.tenant_id "
                        + "where c.tenant_id=? and c.workspace_id=? and c.id=?",
                (rs, row) -> new PartyData(
                        new BusinessParty(rs.getString("issuer_name"), rs.getString("business_identifier"), "BUSINESS_ID", rs.getString("business_identifier"), null),
                        new BusinessParty(rs.getString("business_name"), rs.getString("code"), rs.getString("tax_identifier_type"), rs.getString("tax_identifier_value"), null)),
                tenant, workspace, clientAccountId).stream().findFirst().orElseThrow(() -> notFound("Client account"));
    }

    private BigDecimal allocation(UUID tenant, UUID workspace, UUID paymentId) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from payments.receivable_allocation where tenant_id=? and workspace_id=? and payment_id=?",
                BigDecimal.class, tenant, workspace, paymentId);
    }

    private String evidenceCount(UUID tenant, UUID workspace, String type, UUID subjectId) {
        return String.valueOf(jdbc.queryForObject("select count(*) from business_documents.evidence_object where tenant_id=? and workspace_id=? and subject_type=? and subject_id=? and lifecycle_status='AVAILABLE'",
                Long.class, tenant, workspace, type, subjectId));
    }

    private static DocumentTotals totals(BigDecimal subtotal, BigDecimal total, String currency) {
        BigDecimal tax = total.subtract(subtotal).max(ZERO);
        return new DocumentTotals(subtotal, tax, total, currency);
    }

    private static String joinNotes(String prefix, String notes) { return notes == null || notes.isBlank() ? prefix : prefix + " - " + notes; }
    private static String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static UUID uuid(String value, String label) { try { return UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(label + " is invalid", e); } }
    private static IllegalArgumentException notFound(String value) { return new IllegalArgumentException(value + " not found"); }
    private static IllegalArgumentException unsupported(BusinessDocumentType type, DocumentSubjectType subject) { return new IllegalArgumentException("Document type " + type + " is not supported for " + subject); }

    private record OrderData(String number, Instant createdAt, String status, BusinessParty issuer, BusinessParty buyer, List<DocumentLine> lines,
            DocumentTotals totals, DeliveryInfo delivery, String paymentTerms, String notes, UUID id) { }
    private record PurchaseRequestData(String code, Instant createdAt, String requestedDeliveryDate, Instant deliveryAt, String status,
            BusinessParty issuer, BusinessParty buyer, List<DocumentLine> lines, DocumentTotals totals, String address, String route,
            String paymentTerms, String comments, String reviewNote) { }
    private record DispatchData(String number, String status, Instant issueDate, OrderData order, DeliveryInfo delivery,
            String temperatureStatus, String temperatureSummary) { }
    private record PodData(String receiver, Instant completedAt, String notes, String status, boolean photo, boolean signature, UUID dispatchId) { }
    private record IncidentData(String type, String severity, String description, Instant occurredAt, String resolution, UUID dispatchId) { }
    private record PartyData(BusinessParty issuer, BusinessParty buyer) { }
    private record PaymentData(UUID id, BigDecimal amount, String currency, String method, String status, String providerReference,
            Instant createdAt, UUID receivableId, String receivableNumber, UUID clientAccountId, UUID orderId, String orderNumber) {
        private static PaymentData from(ResultSet rs) throws SQLException {
            return new PaymentData(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("method"),
                    rs.getString("status"), rs.getString("provider_payment_intent_id"), rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("receivable_id", UUID.class), rs.getString("receivable_number"), rs.getObject("client_account_id", UUID.class),
                    rs.getString("order_number") == null ? null : rs.getObject("subject_id", UUID.class), rs.getString("order_number"));
        }
    }
    private record ReceivableData(UUID id, String receivableNumber, BigDecimal amount, BigDecimal amountPaid, String currency, String status,
            Instant createdAt, UUID clientAccountId, String subjectType, UUID subjectId, String orderNumber) { }

    private PaymentData paymentData(ResultSet rs, UUID tenant, UUID workspace, UUID paymentId) throws SQLException {
        UUID clientAccountId = jdbc.queryForObject("select client_account_id from payments.payment where tenant_id=? and workspace_id=? and id=?",
                UUID.class, tenant, workspace, paymentId);
        UUID orderId = "SALES_ORDER".equals(rs.getString("subject_type")) ? rs.getObject("subject_id", UUID.class) : null;
        return new PaymentData(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("method"),
                rs.getString("status"), rs.getString("provider_payment_intent_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("receivable_id", UUID.class), rs.getString("receivable_number"), clientAccountId, orderId, rs.getString("order_number"));
    }
}
