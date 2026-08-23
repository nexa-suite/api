package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.customerrelationships.contract.CustomerAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class ManualSalesOrder {
    private final SalesOrderId id;
    private final SalesOrderNumber number;
    private final TenantId tenantId;
    private final WorkspaceId workspaceId;
    private final CustomerAccountId clientAccountId;
    private final MembershipId createdByMembershipId;
    private final List<SalesOrderLine> lines;
    private final PurchaseRequestPriority priority;
    private final ManualSalesOrderSnapshot snapshot;
    private final Instant createdAt;
    private SalesOrderStatus status;
    private long version;

    private ManualSalesOrder(SalesOrderId id, SalesOrderNumber number, TenantId tenantId, WorkspaceId workspaceId,
                             CustomerAccountId clientAccountId, MembershipId createdByMembershipId,
                             List<SalesOrderLine> lines, PurchaseRequestPriority priority,
                             ManualSalesOrderSnapshot snapshot, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Sales order id is required");
        this.number = Objects.requireNonNull(number, "Sales order number is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
        this.clientAccountId = Objects.requireNonNull(clientAccountId, "Client account id is required");
        this.createdByMembershipId = Objects.requireNonNull(createdByMembershipId, "Creator membership id is required");
        if (lines == null || lines.isEmpty()) throw new SalesOrderInvariantViolation("Manual sales order requires a line");
        this.lines = List.copyOf(lines);
        this.priority = priority == null ? PurchaseRequestPriority.NORMAL : priority;
        this.snapshot = Objects.requireNonNull(snapshot, "Manual sales order snapshot is required");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at is required");
        BigDecimal total = lines.stream().map(SalesOrderLine::lineSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (snapshot.payment().amount().compareTo(total) != 0) {
            throw new SalesOrderInvariantViolation("Manual sales order total does not match payment snapshot");
        }
        this.status = SalesOrderStatus.PENDING;
        this.version = 0;
    }

    public static ManualSalesOrder create(SalesOrderId id, SalesOrderNumber number, TenantId tenantId,
                                          WorkspaceId workspaceId, CustomerAccountId clientAccountId,
                                          MembershipId createdByMembershipId, List<SalesOrderLine> lines,
                                          PurchaseRequestPriority priority, ManualSalesOrderSnapshot snapshot,
                                          Instant createdAt) {
        return new ManualSalesOrder(id, number, tenantId, workspaceId, clientAccountId, createdByMembershipId,
                lines, priority, snapshot, createdAt);
    }

    public void confirm(Instant at) { requirePending(); status = SalesOrderStatus.CONFIRMED; version++; }
    public void reject(String reason) {
        requirePending();
        if (reason == null || reason.isBlank()) throw new SalesOrderInvariantViolation("Sales order rejection reason is required");
        status = SalesOrderStatus.REJECTED; version++;
    }
    public void cancel() { requirePending(); status = SalesOrderStatus.CANCELLED; version++; }

    public SalesOrderId id() { return id; }
    public SalesOrderNumber number() { return number; }
    public TenantId tenantId() { return tenantId; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public CustomerAccountId clientAccountId() { return clientAccountId; }
    public MembershipId createdByMembershipId() { return createdByMembershipId; }
    public List<SalesOrderLine> lines() { return lines; }
    public PurchaseRequestPriority priority() { return priority; }
    public ManualSalesOrderSnapshot snapshot() { return snapshot; }
    public LocalDate requestedDeliveryDate() { return snapshot.delivery().requestedDate(); }
    public BigDecimal total() { return snapshot.payment().amount(); }
    public SalesOrderStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    private void requirePending() {
        if (status != SalesOrderStatus.PENDING) throw new SalesOrderInvariantViolation("Sales order transition is allowed only from PENDING");
    }
}
