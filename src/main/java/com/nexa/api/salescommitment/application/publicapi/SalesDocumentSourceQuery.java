package com.nexa.api.salescommitment.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable Sales Commitment facts required to render business documents. */
public interface SalesDocumentSourceQuery {
    Optional<SalesOrderSnapshot> findOrder(UUID tenantId, UUID workspaceId, UUID orderId);

    Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId, UUID requestId);

    record Line(UUID skuId, UUID familyId, String skuCodeSnapshot, String familyCodeSnapshot,
                String catalogItemId, String itemNameSnapshot, String presentation,
                BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal lineSubtotal,
                String currency) { }

    record SalesOrderSnapshot(UUID id, UUID customerAccountId, String number, Instant createdAt,
                              LocalDate requestedDeliveryDate, String status, String deliverySnapshot,
                              String deliveryAddressSnapshot, String routeSnapshot,
                              String warehouseSelectionSnapshot, String paymentOption, String notes,
                              String currency, BigDecimal totalAmount, List<Line> lines) {
        public SalesOrderSnapshot { lines = List.copyOf(lines); }
    }

    record PurchaseRequestSnapshot(UUID id, UUID customerAccountId, String code, Instant createdAt,
                                   LocalDate requestedDeliveryDate, String status, String paymentOption,
                                   String comments, String reviewNote, String deliveryAddressSnapshot,
                                   String routeSnapshot, String warehouseSelectionSnapshot, List<Line> lines) {
        public PurchaseRequestSnapshot { lines = List.copyOf(lines); }
    }
}
