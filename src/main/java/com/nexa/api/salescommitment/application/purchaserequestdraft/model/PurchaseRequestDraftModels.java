package com.nexa.api.salescommitment.application.purchaserequestdraft.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseRequestDraftModels {
    private PurchaseRequestDraftModels() { }
    public record DraftView(String id, String clientAccountId, String buyerMembershipId, String status, long version,
            LocalDate requestedDeliveryDate, String paymentPreference, String creditResult, String routeProvider,
            List<LineView> lines, DestinationView destination, RouteView route, WarehouseSelectionView warehouseSelection,
            Instant createdAt, Instant updatedAt, Instant submittedAt) {
        public DraftView { lines = List.copyOf(lines == null ? List.of() : lines); }
    }
    public record LineView(String id, String skuId, String skuCode, String presentation, BigDecimal quantity, String unit,
            BigDecimal baseUnitPrice, BigDecimal effectiveUnitPrice, BigDecimal discountAmount, String currency, String notes) { }
    public record DestinationView(String addressId, String snapshot, String schemaVersion) { }
    public record RouteView(String provider, boolean estimated, String snapshot, String schemaVersion, Instant calculatedAt) { }
    public record WarehouseSelectionView(String warehouseId, String snapshot, String schemaVersion, Instant selectedAt) { }
    public record ReviewView(DraftView draft, boolean productsComplete, boolean destinationComplete, boolean routeValidated,
            boolean commercialReviewComplete, boolean readyToSubmit, List<String> missing) {
        public ReviewView { missing = List.copyOf(missing); }
    }
}
