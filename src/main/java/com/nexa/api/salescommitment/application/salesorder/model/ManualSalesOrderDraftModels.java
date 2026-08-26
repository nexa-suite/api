package com.nexa.api.salescommitment.application.salesorder.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ManualSalesOrderDraftModels {
    private ManualSalesOrderDraftModels() { }

    public record DraftView(
            String id,
            String status,
            long version,
            ClientView client,
            LocalDate requestedDeliveryDate,
            String priority,
            String paymentPreference,
            String currency,
            String notes,
            String creditResult,
            List<LineView> lines,
            DeliveryView delivery,
            boolean readyToCreate,
            String salesOrderId,
            Instant createdAt,
            Instant updatedAt,
            Instant submittedAt) {
        public DraftView {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record ClientView(
            String id,
            String code,
            String businessName,
            String commercialName,
            String taxIdentifierType,
            String taxIdentifierValue,
            String status,
            String paymentTerms,
            BigDecimal creditLimit,
            BigDecimal currentExposure,
            BigDecimal availableCredit) { }

    public record LineView(
            String id,
            String skuId,
            String catalogItemId,
            String productFamily,
            String familyCode,
            String skuCode,
            String presentation,
            String unit,
            BigDecimal quantity,
            BigDecimal baseUnitPrice,
            BigDecimal effectiveUnitPrice,
            BigDecimal discountAmount,
            String currency,
            String availabilityStatus,
            String notes) { }

    public record DeliveryView(
            String addressId,
            String addressSnapshot,
            String routeSnapshot,
            String warehouseSnapshot,
            String warehouseId,
            String routeProvider,
            String deliveryNotes) { }

    public record ReviewView(
            DraftView draft,
            boolean clientComplete,
            boolean itemsComplete,
            boolean deliveryComplete,
            boolean readyToCreate,
            List<String> missing) {
        public ReviewView {
            missing = List.copyOf(missing == null ? List.of() : missing);
        }
    }

    public record ClientCommand(
            UUID clientAccountId,
            LocalDate requestedDeliveryDate,
            String priority,
            String paymentPreference,
            String currency,
            String notes) { }

    public record LineCommand(
            UUID skuId,
            String catalogItemId,
            BigDecimal quantity,
            String unit,
            String notes) { }

    public record DeliveryCommand(UUID addressId, String deliveryNotes, String routeProvider) { }
}
