package com.nexa.api.sales.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PurchaseRequest {
	private final PurchaseRequestId id;
	private final ClientAccountId clientAccountId;
	private final String buyerMembershipId;
	private final List<PurchaseRequestLine> lines = new ArrayList<>();
	private PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;
	private PurchaseRequestPriority priority;
	private LocalDate requestedDeliveryDate;
	private String deliveryProfileSnapshot;
	private String paymentOption;
	private String comment;

	private PurchaseRequest(PurchaseRequestId id, ClientAccountId clientAccountId, String buyerMembershipId) {
		this.id = Objects.requireNonNull(id); this.clientAccountId = Objects.requireNonNull(clientAccountId); this.buyerMembershipId = Objects.requireNonNull(buyerMembershipId);
	}
	public static PurchaseRequest draft(PurchaseRequestId id, ClientAccountId clientAccountId, String buyerMembershipId) { return new PurchaseRequest(id, clientAccountId, buyerMembershipId); }
	public void addLine(PurchaseRequestLine line) { ensureEditable(); if (lines.stream().anyMatch(existing -> existing.snapshot().catalogItemId().equals(line.snapshot().catalogItemId()))) throw new IllegalArgumentException("Catalog item already exists in request"); lines.add(Objects.requireNonNull(line)); }
	public void removeLine(String lineId) { ensureEditable(); if (!lines.removeIf(line -> line.id().equals(lineId))) throw new IllegalArgumentException("Purchase request line not found"); }
	public void submit() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new IllegalStateException("Purchase request cannot be submitted"); if (lines.isEmpty()) throw new IllegalStateException("Purchase request requires a line"); status = PurchaseRequestStatus.SUBMITTED; }
	public void startReview() { transition(PurchaseRequestStatus.SUBMITTED, PurchaseRequestStatus.IN_REVIEW); }
	public void requestAdjustment() { transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.NEEDS_ADJUSTMENT); }
	public void approve() { transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.APPROVED); }
	public void reject() { transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.REJECTED); }
	public void cancel() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.SUBMITTED && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new IllegalStateException("Purchase request cannot be cancelled"); status = PurchaseRequestStatus.CANCELLED; }
	public void details(PurchaseRequestPriority priority, LocalDate deliveryDate, String deliveryProfileSnapshot, String paymentOption, String comment) { ensureEditable(); this.priority = priority == null ? PurchaseRequestPriority.NORMAL : priority; this.requestedDeliveryDate = deliveryDate; this.deliveryProfileSnapshot = deliveryProfileSnapshot; this.paymentOption = paymentOption; this.comment = comment; }
	public PurchaseRequestId id() { return id; }
	public ClientAccountId clientAccountId() { return clientAccountId; }
	public String buyerMembershipId() { return buyerMembershipId; }
	public PurchaseRequestStatus status() { return status; }
	public PurchaseRequestPriority priority() { return priority; }
	public LocalDate requestedDeliveryDate() { return requestedDeliveryDate; }
	public String deliveryProfileSnapshot() { return deliveryProfileSnapshot; }
	public String paymentOption() { return paymentOption; }
	public String comment() { return comment; }
	public List<PurchaseRequestLine> lines() { return List.copyOf(lines); }
	private void ensureEditable() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new IllegalStateException("Purchase request is not editable"); }
	private void transition(PurchaseRequestStatus expected, PurchaseRequestStatus next) { if (status != expected) throw new IllegalStateException("Invalid purchase request transition"); status = next; }
}
