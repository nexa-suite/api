package com.nexa.api.payments.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BC-08 storage commands used by the trusted BC-04 order lifecycle. These are
 * internal module contracts, not client commands. The verified current access
 * context is the authorized commercial actor. BC-04 supplies the immutable
 * Buyer identity and relationship snapshot captured when that Buyer selected
 * wallet tender. BC-08 rechecks the Tenant-local BC-02 relationship; clients
 * never select a wallet identity or invoke this port.
 *
 * <p>Implementations participate in the caller's synchronous local database
 * transaction. They do not create provider credits, start payment intents,
 * select order tender, or make calls to external systems.</p>
 */
public interface BuyerWalletReservationCommands {
    Reservation reserveForSalesOrder(CurrentAccessContext context, ReserveCommand command);

    /**
     * Reserves funds for an already-approved Purchase Request conversion. BC-04
     * must supply its locked Tenant-local status and Buyer-consented tender snapshot;
     * this technical command does not authorize direct or manual Sales Orders.
     */
    Reservation reserveForApprovedPurchaseRequestConversion(CurrentAccessContext context,
            ApprovedPurchaseRequestReserveCommand command);

    Reservation consumeForSalesOrder(CurrentAccessContext context, TransitionCommand command);

    /** Consumption performed while an authorized Purchase Request converts into its Sales Order. */
    Reservation consumeForPurchaseRequestConversion(CurrentAccessContext context, TransitionCommand command);

    /** Consumption for the verified SYSTEM_WORKFLOW conversion of an approved Purchase Request. */
    Reservation consumeForApprovedPurchaseRequestConversion(CurrentAccessContext context,
            TransitionCommand command);

    Reservation releaseForSalesOrder(CurrentAccessContext context, TransitionCommand command);

    Refund refundForSalesOrder(CurrentAccessContext context, RefundCommand command);

    enum ReservationStatus {
        RESERVED, CONSUMED, RELEASED
    }

    record ReserveCommand(UUID salesOrderId, UUID buyerMembershipId, UUID buyerAccountId,
                          UUID buyerIdentityId, BigDecimal amountPEN,
                          String idempotencyKey, Instant occurredAt) {
        public ReserveCommand {
            Objects.requireNonNull(salesOrderId, "Sales Order id is required");
            Objects.requireNonNull(buyerMembershipId, "Buyer membership snapshot is required");
            Objects.requireNonNull(buyerAccountId, "Buyer account relationship is required");
            Objects.requireNonNull(buyerIdentityId, "Buyer identity snapshot is required");
            requirePositiveMoney(amountPEN);
            requireIdempotencyKey(idempotencyKey);
            Objects.requireNonNull(occurredAt, "Occurrence time is required");
        }
    }

    record TransitionCommand(UUID salesOrderId,
                             String idempotencyKey, Instant occurredAt) {
        public TransitionCommand {
            Objects.requireNonNull(salesOrderId, "Sales Order id is required");
            requireIdempotencyKey(idempotencyKey);
            Objects.requireNonNull(occurredAt, "Occurrence time is required");
        }
    }

    record ApprovedPurchaseRequestReserveCommand(UUID purchaseRequestId, String purchaseRequestStatus,
            String paymentOption, UUID walletBeneficiaryIdentityId, ReserveCommand reservation) {
        public ApprovedPurchaseRequestReserveCommand {
            Objects.requireNonNull(purchaseRequestId, "Purchase Request id is required");
            Objects.requireNonNull(purchaseRequestStatus, "Purchase Request status snapshot is required");
            Objects.requireNonNull(paymentOption, "Purchase Request payment option is required");
            Objects.requireNonNull(walletBeneficiaryIdentityId, "Buyer wallet consent snapshot is required");
            Objects.requireNonNull(reservation, "Reservation command is required");
            if (!walletBeneficiaryIdentityId.equals(reservation.buyerIdentityId())) {
                throw new IllegalArgumentException("Wallet reservation beneficiary must match Buyer consent snapshot");
            }
        }
    }

    record RefundCommand(UUID salesOrderId, UUID refundId, BigDecimal amountPEN,
                         String idempotencyKey, Instant occurredAt) {
        public RefundCommand {
            Objects.requireNonNull(salesOrderId, "Sales Order id is required");
            Objects.requireNonNull(refundId, "Refund id is required");
            requirePositiveMoney(amountPEN);
            requireIdempotencyKey(idempotencyKey);
            Objects.requireNonNull(occurredAt, "Occurrence time is required");
        }
    }

    record Reservation(UUID id, UUID salesOrderId, BigDecimal amountPEN,
                       ReservationStatus status) {
        public Reservation {
            Objects.requireNonNull(id, "Reservation id is required");
            Objects.requireNonNull(salesOrderId, "Sales Order id is required");
            requirePositiveMoney(amountPEN);
            Objects.requireNonNull(status, "Reservation status is required");
        }
    }

    record Refund(UUID ledgerEntryId, UUID refundId, BigDecimal amountPEN) {
        public Refund {
            Objects.requireNonNull(ledgerEntryId, "Ledger entry id is required");
            Objects.requireNonNull(refundId, "Refund id is required");
            requirePositiveMoney(amountPEN);
        }
    }

    private static void requirePositiveMoney(BigDecimal value) {
        Objects.requireNonNull(value, "PEN amount is required");
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 4 || value.precision() > 19) {
            throw new IllegalArgumentException("PEN amount must be positive with at most four decimal places");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
    }
}
