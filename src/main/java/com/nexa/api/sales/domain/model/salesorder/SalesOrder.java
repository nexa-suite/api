package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.ClientAccountId;
import com.nexa.api.sales.domain.PurchaseRequestId;
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
	private final PurchaseRequestId sourcePurchaseRequestId;
	private final List<SalesOrderLine> lines;
	private final BigDecimal totalSnapshot;
	private final Instant createdAt;
	private SalesOrderStatus status;
	private Instant confirmedAt;

	private SalesOrder(ApprovedPurchaseRequestSnapshot snapshot, SalesOrderId id, SalesOrderNumber number, Instant createdAt) {
		this.id=Objects.requireNonNull(id); this.number=Objects.requireNonNull(number); this.tenantId=snapshot.tenantId(); this.workspaceId=snapshot.workspaceId(); this.clientAccountId=snapshot.clientAccountId(); this.sourcePurchaseRequestId=snapshot.purchaseRequestId(); this.lines=List.copyOf(snapshot.lines()); this.totalSnapshot=snapshot.totalSnapshot(); this.createdAt=Objects.requireNonNull(createdAt); this.status=SalesOrderStatus.PENDING;
	}
	public static SalesOrder fromApprovedSnapshot(ApprovedPurchaseRequestSnapshot snapshot, SalesOrderId id, SalesOrderNumber number, Instant createdAt) { return new SalesOrder(Objects.requireNonNull(snapshot), id, number, createdAt); }
	public void confirm(Instant at) { require(SalesOrderStatus.PENDING); status=SalesOrderStatus.CONFIRMED; confirmedAt=Objects.requireNonNull(at); }
	public void reject() { require(SalesOrderStatus.PENDING); status=SalesOrderStatus.REJECTED; }
	public void cancel() { if (status != SalesOrderStatus.PENDING && status != SalesOrderStatus.CONFIRMED) throw new SalesOrderInvariantViolation("Sales order cannot be cancelled"); status=SalesOrderStatus.CANCELLED; }
	public SalesOrderId id(){return id;} public SalesOrderNumber number(){return number;} public TenantId tenantId(){return tenantId;} public WorkspaceId workspaceId(){return workspaceId;} public ClientAccountId clientAccountId(){return clientAccountId;} public PurchaseRequestId sourcePurchaseRequestId(){return sourcePurchaseRequestId;} public List<SalesOrderLine> lines(){return lines;} public BigDecimal totalSnapshot(){return totalSnapshot;} public Instant createdAt(){return createdAt;} public SalesOrderStatus status(){return status;} public Instant confirmedAt(){return confirmedAt;}
	private void require(SalesOrderStatus expected){if(status!=expected)throw new SalesOrderInvariantViolation("Sales order transition is not allowed");}
}
