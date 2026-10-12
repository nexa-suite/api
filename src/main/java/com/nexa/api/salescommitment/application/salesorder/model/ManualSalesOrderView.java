package com.nexa.api.salescommitment.application.salesorder.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@NamedInterface(value = "sales-public", propagate = false)
public record ManualSalesOrderView(String id, String number, String tenantId, String workspaceId,
                                   String clientAccountId, String createdByMembershipId,
                                   PurchaseRequestPriority priority, LocalDate requestedDeliveryDate,
                                   SnapshotView snapshot, String currency, BigDecimal total,
                                   String status, Instant createdAt, Instant updatedAt, long version,
                                   List<SalesOrderLineView> lines) {
    public ManualSalesOrderView {
        lines = List.copyOf(lines);
    }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "ManualSalesOrderSnapshot")
    public record SnapshotView(DeliveryView delivery, CommercialView commercial, PaymentView payment,
                               Instant capturedAt, String notes) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "DeliverySnapshot")
    public record DeliveryView(LocalDate requestedDate, String notes, DeliveryAddressView address,
                               WarehouseView warehouse, RouteView route) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "DeliveryAddressSnapshot")
    public record DeliveryAddressView(String id, String label, Address address, boolean defaultAddress) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "WarehouseSnapshot")
    public record WarehouseView(String id, String code, String name, String address, String selectionReason,
                               String serviceStatus, int priority, boolean preferred, Instant selectedAt,
                               BigDecimal latitude, BigDecimal longitude) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "RouteSnapshot")
    public record RouteView(String provider, String reference, String originLabel, String destinationLabel,
                            long distanceMeters, long durationSeconds, String previewUrl,
                            BigDecimal originLatitude, BigDecimal originLongitude,
                            BigDecimal destinationLatitude, BigDecimal destinationLongitude,
                            Instant calculatedAt, String mode, String path) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "CommercialSnapshot")
    public record CommercialView(String clientAccountId, String businessName, String commercialName,
                                 String taxIdentifier, CreditView credit, PaymentTermsView paymentTerms,
                                 boolean active) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "CreditProfile")
    public record CreditView(BigDecimal limit, BigDecimal used,
                             @Schema(allowableValues = {"AVAILABLE", "ATTENTION", "BLOCKED", "OVERDUE"})
                             String status) {
        public BigDecimal available() { return limit.subtract(used).max(BigDecimal.ZERO); }
    }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "PaymentTerms")
    public record PaymentTermsView(String code, String label, int dueDays, boolean credit) { }

    @NamedInterface(value = "sales-public", propagate = false)
    @Schema(name = "PaymentSnapshot")
    public record PaymentView(
            @Schema(allowableValues = {"CREDIT_LINE", "BANK_TRANSFER", "CARD_STRIPE", "WALLET",
                    "CASH", "CASH_ON_DELIVERY", "PREPAID", "IMMEDIATE"})
            String option, String termsCode, BigDecimal amount, String currency,
            boolean creditAuthorized) { }
}
