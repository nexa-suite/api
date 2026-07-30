package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class SalesOrder {
	private final SalesOrderId id;
	private final SalesOrderNumber number;
	private final TenantId tenantId;
	private final WorkspaceId workspaceId;
	private final ClientAccountId clientAccountId;
	private final BuyerMembershipId buyerMembershipId;
	private final PurchaseRequestId sourcePurchaseRequestId;
	private final List<SalesOrderLine> lines;
	private final String currency;
	private final BigDecimal totalSnapshot;
	private final Instant createdAt;
	private SalesOrderStatus status;
	private Instant confirmedAt;
	private String rejectionReason;
	private long version;

	private SalesOrder(ApprovedPurchaseRequestSnapshot snapshot, SalesOrderId id, SalesOrderNumber number, Instant createdAt) {
		this.id = Objects.requireNonNull(id); this.number = Objects.requireNonNull(number);
		this.tenantId = snapshot.tenantId(); this.workspaceId = snapshot.workspaceId(); this.clientAccountId = snapshot.clientAccountId();
		this.buyerMembershipId = snapshot.buyerMembershipId(); this.sourcePurchaseRequestId = snapshot.purchaseRequestId();
		this.lines = List.copyOf(snapshot.lines()); this.currency = snapshot.currency(); this.totalSnapshot = snapshot.totalSnapshot();
		this.createdAt = Objects.requireNonNull(createdAt); this.status = SalesOrderStatus.PENDING; this.version = 0;
	}

	public static SalesOrder fromApprovedSnapshot(ApprovedPurchaseRequestSnapshot snapshot, SalesOrderId id,
			SalesOrderNumber number, Instant createdAt) { return new SalesOrder(Objects.requireNonNull(snapshot), id, number, createdAt); }

	public static SalesOrder rehydrate(SalesOrderId id, SalesOrderNumber number, TenantId tenantId, WorkspaceId workspaceId,
			ClientAccountId clientAccountId, BuyerMembershipId buyerMembershipId, PurchaseRequestId sourcePurchaseRequestId,
			List<SalesOrderLine> lines, String currency, BigDecimal totalSnapshot, Instant createdAt,
			SalesOrderStatus status, Instant confirmedAt, String rejectionReason, long version) {
		ApprovedPurchaseRequestSnapshot snapshot = new ApprovedPurchaseRequestSnapshot(tenantId, workspaceId, clientAccountId,
				buyerMembershipId, sourcePurchaseRequestId, lines, currency, totalSnapshot);
		SalesOrder order = new SalesOrder(snapshot, id, number, createdAt);
		order.status = Objects.requireNonNull(status); order.confirmedAt = confirmedAt;
		order.rejectionReason = rejectionReason; order.version = version;
		return order;
	}

	public void confirm(Instant at) { requirePending(); status = SalesOrderStatus.CONFIRMED; confirmedAt = Objects.requireNonNull(at); }
	public void reject(String reason) { requirePending(); if (reason == null || reason.isBlank()) throw new SalesOrderInvariantViolation("Sales order rejection reason is required"); rejectionReason = reason.trim(); status = SalesOrderStatus.REJECTED; }
	/** Compatibility helper; HTTP/application commands always provide an explicit reason. */
	public void reject() { reject("Rejected by commercial review"); }
	public void cancel() { requirePending(); status = SalesOrderStatus.CANCELLED; }

	public SalesOrderId id() { return id; }
	public SalesOrderNumber number() { return number; }
	public TenantId tenantId() { return tenantId; }
	public WorkspaceId workspaceId() { return workspaceId; }
	public ClientAccountId clientAccountId() { return clientAccountId; }
	public BuyerMembershipId buyerMembershipId() { return buyerMembershipId; }
	public PurchaseRequestId sourcePurchaseRequestId() { return sourcePurchaseRequestId; }
	public List<SalesOrderLine> lines() { return lines; }
	public String currency() { return currency; }
	public BigDecimal totalSnapshot() { return totalSnapshot; }
	public Instant createdAt() { return createdAt; }
	public SalesOrderStatus status() { return status; }
	public Instant confirmedAt() { return confirmedAt; }
	public String rejectionReason() { return rejectionReason; }
	public long version() { return version; }

	private void requirePending() { if (status != SalesOrderStatus.PENDING) throw new SalesOrderInvariantViolation("Sales order transition is allowed only from PENDING"); }
}
