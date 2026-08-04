package com.nexa.api.invoicing.application.model;

import com.nexa.api.invoicing.domain.model.businessdocument.BusinessDocumentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, typed read models used by the business-document renderers.
 *
 * These are deliberately separate from transport DTOs and persistence rows. A
 * renderer can therefore only consume business facts that were resolved by the
 * document projection port; it cannot invent fields from an arbitrary map.
 */
public final class BusinessDocumentProjections {
    private BusinessDocumentProjections() { }

    public interface DocumentProjection {
        BusinessDocumentType type();
        String subjectId();
        String reference();
        Instant issueDate();
        String status();
        BusinessParty issuer();
        BusinessParty buyer();
        List<DocumentLine> lines();
        DocumentTotals totals();
        DeliveryInfo delivery();
        String paymentTerms();
        String notes();
    }

    public record BusinessParty(
            String legalName,
            String businessIdentifier,
            String taxIdentifierType,
            String taxIdentifierValue,
            String address) {
        public BusinessParty {
            legalName = required(legalName, "Party legal name");
            businessIdentifier = optional(businessIdentifier);
            taxIdentifierType = optional(taxIdentifierType);
            taxIdentifierValue = optional(taxIdentifierValue);
            address = optional(address);
        }
    }

    public record DocumentLine(
            String skuCode,
            String productFamily,
            String presentation,
            BigDecimal quantity,
            String uom,
            BigDecimal baseUnitPrice,
            BigDecimal discount,
            BigDecimal effectiveUnitPrice,
            BigDecimal lineTotal,
            String currency,
            BigDecimal weight) {
        public DocumentLine {
            skuCode = required(skuCode, "SKU code");
            productFamily = required(productFamily, "Product family");
            presentation = required(presentation, "Presentation");
            quantity = positive(quantity, "Quantity");
            uom = required(uom, "UOM");
            baseUnitPrice = nonNegative(baseUnitPrice, "Base unit price");
            discount = nonNegative(discount, "Discount");
            effectiveUnitPrice = nonNegative(effectiveUnitPrice, "Effective unit price");
            lineTotal = nonNegative(lineTotal, "Line total");
            currency = required(currency, "Currency");
            weight = weight == null ? null : nonNegative(weight, "Weight");
        }
    }

    public record DocumentTotals(BigDecimal subtotal, BigDecimal tax, BigDecimal total, String currency) {
        public DocumentTotals {
            subtotal = nonNegative(subtotal, "Subtotal");
            tax = nonNegative(tax, "Tax");
            total = nonNegative(total, "Total");
            currency = required(currency, "Currency");
        }
    }

    public record DeliveryInfo(
            String address,
            String warehouse,
            String route,
            String dispatch,
            String carrier,
            String vehicle,
            Instant deliveryAt) {
        public DeliveryInfo {
            address = optional(address);
            warehouse = optional(warehouse);
            route = optional(route);
            dispatch = optional(dispatch);
            carrier = optional(carrier);
            vehicle = optional(vehicle);
        }

        public static DeliveryInfo empty() {
            return new DeliveryInfo(null, null, null, null, null, null, null);
        }
    }

    public record OrderSummaryProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String orderNumber,
            Instant orderDate,
            String status,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes) implements DocumentProjection {
        public OrderSummaryProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            orderNumber = required(orderNumber, "Order number");
            orderDate = Objects.requireNonNull(orderDate, "Order date is required");
            status = required(status, "Order status");
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.ORDER_SUMMARY; }
        @Override public String reference() { return orderNumber; }
        @Override public Instant issueDate() { return orderDate; }
    }

    public record PurchaseRequestSummaryProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String requestCode,
            Instant requestDate,
            String requestedDeliveryDate,
            String status,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes,
            String reviewNote) implements DocumentProjection {
        public PurchaseRequestSummaryProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            requestCode = required(requestCode, "Request code");
            requestDate = Objects.requireNonNull(requestDate, "Request date is required");
            requestedDeliveryDate = optional(requestedDeliveryDate);
            status = required(status, "Request status");
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
            reviewNote = optional(reviewNote);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.PURCHASE_REQUEST_SUMMARY; }
        @Override public String reference() { return requestCode; }
        @Override public Instant issueDate() { return requestDate; }
    }

    public record CommercialInvoiceDraftProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String invoiceNumber,
            String orderReference,
            Instant issueDate,
            String status,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes) implements DocumentProjection {
        public CommercialInvoiceDraftProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            invoiceNumber = required(invoiceNumber, "Invoice number");
            orderReference = required(orderReference, "Order reference");
            issueDate = Objects.requireNonNull(issueDate, "Issue date is required");
            status = required(status, "Invoice status");
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.COMMERCIAL_INVOICE_DRAFT; }
        @Override public String reference() { return invoiceNumber; }
    }

    public record DeliveryGuideDraftProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String dispatchNumber,
            String salesOrderReference,
            Instant issueDate,
            String status,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes) implements DocumentProjection {
        public DeliveryGuideDraftProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            dispatchNumber = required(dispatchNumber, "Dispatch number");
            salesOrderReference = required(salesOrderReference, "Sales order reference");
            issueDate = Objects.requireNonNull(issueDate, "Issue date is required");
            status = required(status, "Dispatch status");
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.DELIVERY_GUIDE_DRAFT; }
        @Override public String reference() { return dispatchNumber; }
    }

    public record PodReportProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String dispatchNumber,
            String salesOrderReference,
            Instant issueDate,
            String status,
            String receiver,
            String conformity,
            String temperatureSummary,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes,
            String evidenceReferences) implements DocumentProjection {
        public PodReportProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            dispatchNumber = required(dispatchNumber, "Dispatch number");
            salesOrderReference = required(salesOrderReference, "Sales order reference");
            issueDate = Objects.requireNonNull(issueDate, "Issue date is required");
            status = required(status, "POD status");
            receiver = required(receiver, "Receiver");
            conformity = required(conformity, "Conformity");
            temperatureSummary = optional(temperatureSummary);
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
            evidenceReferences = optional(evidenceReferences);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.POD_REPORT; }
        @Override public String reference() { return dispatchNumber; }
    }

    public record IncidentReportProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String dispatchNumber,
            String salesOrderReference,
            Instant issueDate,
            String status,
            String incidentType,
            String severity,
            String description,
            String temperatureSummary,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes,
            String resolution,
            String evidenceReferences) implements DocumentProjection {
        public IncidentReportProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            dispatchNumber = required(dispatchNumber, "Dispatch number");
            salesOrderReference = required(salesOrderReference, "Sales order reference");
            issueDate = Objects.requireNonNull(issueDate, "Issue date is required");
            status = required(status, "Incident status");
            incidentType = required(incidentType, "Incident type");
            severity = required(severity, "Incident severity");
            description = required(description, "Incident description");
            temperatureSummary = optional(temperatureSummary);
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
            resolution = optional(resolution);
            evidenceReferences = optional(evidenceReferences);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.INCIDENT_REPORT; }
        @Override public String reference() { return dispatchNumber; }
    }

    public record PaymentReceiptProjection(
            String subjectId,
            BusinessParty issuer,
            BusinessParty buyer,
            String paymentReference,
            String receivableReference,
            String orderReference,
            Instant issueDate,
            String status,
            String method,
            BigDecimal paidAmount,
            BigDecimal allocation,
            String providerReference,
            List<DocumentLine> lines,
            DocumentTotals totals,
            DeliveryInfo delivery,
            String paymentTerms,
            String notes) implements DocumentProjection {
        public PaymentReceiptProjection {
            subjectId = required(subjectId, "Subject id");
            issuer = Objects.requireNonNull(issuer, "Issuer is required");
            buyer = Objects.requireNonNull(buyer, "Buyer is required");
            paymentReference = required(paymentReference, "Payment reference");
            receivableReference = optional(receivableReference);
            orderReference = optional(orderReference);
            issueDate = Objects.requireNonNull(issueDate, "Issue date is required");
            status = required(status, "Payment status");
            method = required(method, "Payment method");
            paidAmount = positive(paidAmount, "Paid amount");
            allocation = nonNegative(allocation, "Allocation");
            providerReference = optional(providerReference);
            lines = immutableLines(lines);
            totals = Objects.requireNonNull(totals, "Totals are required");
            delivery = Objects.requireNonNull(delivery, "Delivery is required");
            paymentTerms = optional(paymentTerms);
            notes = optional(notes);
        }

        @Override public BusinessDocumentType type() { return BusinessDocumentType.PAYMENT_RECEIPT; }
        @Override public String reference() { return paymentReference; }
    }

    private static List<DocumentLine> immutableLines(List<DocumentLine> lines) {
        return List.copyOf(Objects.requireNonNull(lines, "Document lines are required"));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(label + " must be non-negative");
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }
}
