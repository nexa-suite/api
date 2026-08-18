package com.nexa.api.sales.domain.model.buyerrequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestLine;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestStatus;

import java.util.List;
import java.util.Objects;

public final class BuyerRequest {
    private final PurchaseRequestId id;
    private final String clientAccountId;
    private final BuyerMembershipId buyerMembershipId;
    private final List<PurchaseRequestLine> lines;
    private final BuyerRequestSnapshot snapshot;
    private PurchaseRequestStatus status;
    private long version;

    private BuyerRequest(PurchaseRequestId id, String clientAccountId, BuyerMembershipId buyerMembershipId,
                         List<PurchaseRequestLine> lines, BuyerRequestSnapshot snapshot) {
        this.id = Objects.requireNonNull(id, "Buyer request id is required");
        this.clientAccountId = required(clientAccountId, "Client account id");
        this.buyerMembershipId = Objects.requireNonNull(buyerMembershipId, "Buyer membership id is required");
        if (lines == null || lines.isEmpty()) throw new SalesInvariantViolation("Buyer request requires a line");
        this.lines = List.copyOf(lines);
        this.snapshot = Objects.requireNonNull(snapshot, "Buyer request snapshot is required");
        this.status = PurchaseRequestStatus.DRAFT;
        this.version = 0;
    }

    public static BuyerRequest draft(PurchaseRequestId id, String clientAccountId, BuyerMembershipId buyerMembershipId,
                                     List<PurchaseRequestLine> lines, BuyerRequestSnapshot snapshot) {
        return new BuyerRequest(id, clientAccountId, buyerMembershipId, lines, snapshot);
    }

    public void submit() {
        if (status != PurchaseRequestStatus.DRAFT) throw new SalesInvariantViolation("Buyer request cannot be submitted");
        status = PurchaseRequestStatus.SUBMITTED;
        version++;
    }

    public PurchaseRequestId id() { return id; }
    public String clientAccountId() { return clientAccountId; }
    public BuyerMembershipId buyerMembershipId() { return buyerMembershipId; }
    public List<PurchaseRequestLine> lines() { return lines; }
    public BuyerRequestSnapshot snapshot() { return snapshot; }
    public PurchaseRequestStatus status() { return status; }
    public long version() { return version; }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new SalesInvariantViolation(label + " is required");
        return value.trim();
    }
}
