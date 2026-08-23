package com.nexa.api.sales.application.salesorder.service;

import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.sales.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.customerrelationships.contract.CustomerAccountId;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrder;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Creates direct Sales orders after resolving every cross-context fact into snapshots. */
public class ManualSalesOrderService implements ManualSalesOrderUseCase {
    private final SalesSnapshotAssembler snapshots;
    private final ManualSalesOrderPersistencePort persistence;

    public ManualSalesOrderService(SalesSnapshotAssembler snapshots,
                                   ManualSalesOrderPersistencePort persistence) {
        this.snapshots = snapshots;
        this.persistence = persistence;
    }

    @Override
    @Transactional
    public ManualSalesOrderView create(CurrentAccessContext context, CreateManualSalesOrderCommand command,
                                       String idempotencyKey) {
        requireCommercialWrite(context);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new IdempotencyKeyRequiredException();
        }
        if (command == null) throw new IllegalArgumentException("Manual sales order command is required");

        String tenant = context.tenantId().toString();
        String workspace = context.workspaceId().toString();
        String actor = context.membershipId().toString();
        String requestHash = requestHash(command);
        var replay = persistence.findByIdempotency(tenant, workspace, actor, idempotencyKey, requestHash);
        if (replay.isPresent()) return replay.get();

        var assembled = snapshots.manual(context, command.clientAccountId(), command.addressId(), command.manualAddress(),
                command.requestedDeliveryDate(), command.deliveryNotes(), command.notes(), command.warehouseId(), command.routeProvider(),
                command.paymentOption(), command.priority(), command.currency(), command.lines());
        var identity = persistence.nextIdentity(tenant, workspace);
        var order = ManualSalesOrder.create(identity.id(), identity.number(),
                new TenantId(context.tenantId().value()), new WorkspaceId(context.workspaceId().value()),
                new CustomerAccountId(assembled.snapshot().commercial().clientAccountId()),
                new MembershipId(context.membershipId().value()), assembled.lines(), assembled.priority(),
                assembled.snapshot(), java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
        return persistence.save(order, actor, idempotencyKey, requestHash, System.currentTimeMillis());
    }

    private static void requireCommercialWrite(CurrentAccessContext context) {
        context.requirePermission(Permission.SALES_WRITE);
    }

    private static String requestHash(CreateManualSalesOrderCommand command) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, command.clientAccountId());
        append(canonical, command.addressId());
        append(canonical, command.manualAddress());
        append(canonical, command.requestedDeliveryDate());
        append(canonical, command.deliveryNotes());
        append(canonical, command.warehouseId());
        append(canonical, command.routeProvider());
        append(canonical, command.paymentOption());
        append(canonical, command.priority());
        append(canonical, command.currency());
        append(canonical, command.notes());
        for (var line : command.lines()) {
            append(canonical, line.skuId());
            append(canonical, line.catalogItemId());
            append(canonical, line.quantity());
            append(canonical, line.unit());
            append(canonical, line.notes());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void append(StringBuilder canonical, Object value) {
        String text = value == null ? "<null>" : value.toString().trim();
        canonical.append(text.length()).append(':').append(text).append('|');
    }
}
