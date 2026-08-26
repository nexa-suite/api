package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PurchaseRequest {
	private final PurchaseRequestId id;
	private final String clientAccountId;
	private final BuyerMembershipId buyerMembershipId;
	private final List<PurchaseRequestLine> lines = new ArrayList<>();
	private PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;
	private PurchaseRequestPriority priority = PurchaseRequestPriority.NORMAL;
	private RequestedDeliveryDate requestedDeliveryDate;
	private DeliveryProfileSnapshot deliveryProfile;
	private PaymentOption paymentOption;
	private RequestComment comment;
	private String reviewNote;

	private PurchaseRequest(PurchaseRequestId id, String clientAccountId, BuyerMembershipId buyerMembershipId) {
		this.id = Objects.requireNonNull(id);
		if (clientAccountId == null || clientAccountId.isBlank()) throw new SalesInvariantViolation("Client account is required");
		this.clientAccountId = clientAccountId.trim();
		this.buyerMembershipId = Objects.requireNonNull(buyerMembershipId);
	}
	public static PurchaseRequest draft(PurchaseRequestId id, String clientAccountId, BuyerMembershipId buyerMembershipId) { return new PurchaseRequest(id, clientAccountId, buyerMembershipId); }
	public static PurchaseRequest rehydrate(PurchaseRequestId id, String clientAccountId, BuyerMembershipId buyerMembershipId,
			PurchaseRequestStatus status, PurchaseRequestPriority priority, RequestedDeliveryDate deliveryDate,
			DeliveryProfileSnapshot deliveryProfile, PaymentOption paymentOption, RequestComment comment, String reviewNote,
			List<PurchaseRequestLine> lines) {
		PurchaseRequest request = new PurchaseRequest(id, clientAccountId, buyerMembershipId);
		request.status = Objects.requireNonNull(status);
		request.priority = priority == null ? PurchaseRequestPriority.NORMAL : priority;
		request.requestedDeliveryDate = deliveryDate;
		request.deliveryProfile = deliveryProfile;
		request.paymentOption = paymentOption;
		request.comment = comment;
		request.reviewNote = reviewNote;
		if (lines == null) throw new SalesInvariantViolation("Purchase request lines are required for rehydration");
		for (PurchaseRequestLine line : lines) {
			if (request.lines.stream().anyMatch(existing -> existing.catalogItem().catalogItemId().equals(line.catalogItem().catalogItemId()))) {
				throw new SalesInvariantViolation("Catalog item already exists in request");
			}
			request.lines.add(Objects.requireNonNull(line));
		}
		return request;
	}
	public void updateDetails(PurchaseRequestPriority priority, RequestedDeliveryDate deliveryDate, DeliveryProfileSnapshot deliveryProfile, PaymentOption paymentOption, RequestComment comment) {
		ensureEditable(); this.priority = priority == null ? PurchaseRequestPriority.NORMAL : priority; this.requestedDeliveryDate = deliveryDate; this.deliveryProfile = deliveryProfile; this.paymentOption = paymentOption; this.comment = comment;
	}
	public void addLine(PurchaseRequestLine line) {
		ensureEditable(); Objects.requireNonNull(line);
		if (lines.stream().anyMatch(existing -> existing.catalogItem().catalogItemId().equals(line.catalogItem().catalogItemId()))) throw new SalesInvariantViolation("Catalog item already exists in request");
		lines.add(line);
	}
	public void removeLine(PurchaseRequestLineId lineId) {
		ensureEditable(); if (!lines.removeIf(line -> line.id().equals(lineId))) throw new SalesInvariantViolation("Purchase request line not found");
	}
	public void submit() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new SalesInvariantViolation("Purchase request cannot be submitted"); if (lines.isEmpty()) throw new SalesInvariantViolation("Purchase request requires a line"); status = PurchaseRequestStatus.SUBMITTED; }
	public void startReview() { transition(PurchaseRequestStatus.SUBMITTED, PurchaseRequestStatus.IN_REVIEW); }
	public void requestAdjustment(String note) { validateReviewNote(note); transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.NEEDS_ADJUSTMENT); reviewNote = note == null ? null : note.trim(); }
	public void approve(String note) { validateReviewNote(note); transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.APPROVED); reviewNote = note == null ? null : note.trim(); }
	public void reject(String note) { validateReviewNote(note); transition(PurchaseRequestStatus.IN_REVIEW, PurchaseRequestStatus.REJECTED); reviewNote = note == null ? null : note.trim(); }
	public void convertToOrder() { transition(PurchaseRequestStatus.APPROVED, PurchaseRequestStatus.CONVERTED_TO_ORDER); }
	public void expire() { if (status == PurchaseRequestStatus.CONVERTED_TO_ORDER || status == PurchaseRequestStatus.REJECTED || status == PurchaseRequestStatus.CANCELLED || status == PurchaseRequestStatus.WITHDRAWN || status == PurchaseRequestStatus.EXPIRED) throw new SalesInvariantViolation("Purchase request cannot be expired"); status = PurchaseRequestStatus.EXPIRED; }
	public void withdraw() { if (status == PurchaseRequestStatus.CONVERTED_TO_ORDER || status == PurchaseRequestStatus.REJECTED || status == PurchaseRequestStatus.CANCELLED || status == PurchaseRequestStatus.EXPIRED || status == PurchaseRequestStatus.WITHDRAWN) throw new SalesInvariantViolation("Purchase request cannot be withdrawn"); status = PurchaseRequestStatus.WITHDRAWN; }
	public void cancel() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.SUBMITTED && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new SalesInvariantViolation("Purchase request cannot be cancelled"); status = PurchaseRequestStatus.CANCELLED; }
	public PurchaseRequestId id() { return id; }
	public String clientAccountId() { return clientAccountId; }
	public BuyerMembershipId buyerMembershipId() { return buyerMembershipId; }
	public PurchaseRequestStatus status() { return status; }
	public PurchaseRequestPriority priority() { return priority; }
	public RequestedDeliveryDate requestedDeliveryDate() { return requestedDeliveryDate; }
	public DeliveryProfileSnapshot deliveryProfile() { return deliveryProfile; }
	public PaymentOption paymentOption() { return paymentOption; }
	public RequestComment comment() { return comment; }
	public String reviewNote() { return reviewNote; }
	public List<PurchaseRequestLine> lines() { return List.copyOf(lines); }
	private void ensureEditable() { if (status != PurchaseRequestStatus.DRAFT && status != PurchaseRequestStatus.NEEDS_ADJUSTMENT) throw new SalesInvariantViolation("Purchase request is not editable"); }
	private void transition(PurchaseRequestStatus expected, PurchaseRequestStatus next) { if (status != expected) throw new SalesInvariantViolation("Invalid purchase request transition"); status = next; }
	private static void validateReviewNote(String note) { if (note != null && note.length() > 2000) throw new SalesInvariantViolation("Review note is too long"); }
}
