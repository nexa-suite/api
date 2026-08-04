package com.nexa.api.sales.application.salesorder.service;

import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderDraft;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderDraftStatus;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Application use case for the four-step, resumable Sales manual-order workflow. */
public class ManualSalesOrderDraftService implements ManualSalesOrderDraftUseCase {
    private final ManualSalesOrderDraftPersistencePort persistence;
    private final ManualSalesOrderUseCase manualOrders;
    private final ManualSalesOrderPersistencePort orderPersistence;

    public ManualSalesOrderDraftService(ManualSalesOrderDraftPersistencePort persistence,
                                        ManualSalesOrderUseCase manualOrders,
                                        ManualSalesOrderPersistencePort orderPersistence) {
        this.persistence = persistence;
        this.manualOrders = manualOrders;
        this.orderPersistence = orderPersistence;
    }

    @Override
    @Transactional
    public ManualSalesOrderDraftModels.DraftView create(CurrentAccessContext context, String idempotencyKey) {
        write(context);
        requireIdempotencyKey(idempotencyKey);
        return persistence.create(context, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public ManualSalesOrderDraftModels.DraftView get(CurrentAccessContext context, UUID draftId) {
        read(context);
        return persistence.get(context, draftId);
    }

    @Override
    @Transactional
    public ManualSalesOrderDraftModels.DraftView updateClient(CurrentAccessContext context, UUID draftId, long version,
                                                              ManualSalesOrderDraftModels.ClientCommand command) {
        write(context);
        if (command == null) throw new IllegalArgumentException("Manual order client conditions are required");
        return persistence.updateClient(context, draftId, version, command);
    }

    @Override
    @Transactional
    public ManualSalesOrderDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId, long version,
                                                              List<ManualSalesOrderDraftModels.LineCommand> lines) {
        write(context);
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Manual order requires at least one SKU");
        return persistence.replaceLines(context, draftId, version, lines);
    }

    @Override
    @Transactional
    public ManualSalesOrderDraftModels.DraftView updateDelivery(CurrentAccessContext context, UUID draftId, long version,
                                                                ManualSalesOrderDraftModels.DeliveryCommand command) {
        write(context);
        if (command == null || command.addressId() == null) throw new IllegalArgumentException("Delivery address is required");
        return persistence.updateDelivery(context, draftId, version, command);
    }

    @Override
    @Transactional(readOnly = true)
    public ManualSalesOrderDraftModels.ReviewView review(CurrentAccessContext context, UUID draftId) {
        read(context);
        ManualSalesOrderDraftModels.DraftView draft = persistence.get(context, draftId);
        boolean client = draft.client() != null
                && "ACTIVE".equalsIgnoreCase(draft.client().status())
                && !"UNAVAILABLE".equalsIgnoreCase(draft.creditResult())
                && draft.requestedDeliveryDate() != null
                && draft.paymentPreference() != null
                && draft.priority() != null;
        boolean items = !draft.lines().isEmpty()
                && draft.lines().stream().allMatch(line -> "AVAILABLE".equalsIgnoreCase(line.availabilityStatus()));
        boolean delivery = draft.delivery() != null
                && draft.delivery().addressId() != null
                && draft.delivery().warehouseId() != null
                && draft.delivery().routeSnapshot() != null;
        List<String> missing = new java.util.ArrayList<>();
        if (!client) missing.add("client");
        if (!items) missing.add("items");
        if (!delivery) missing.add("delivery");
        boolean ready = client && items && delivery
                && ManualSalesOrderDraftStatus.READY_TO_CREATE.name().equals(draft.status());
        return new ManualSalesOrderDraftModels.ReviewView(draft, client, items, delivery, ready, missing);
    }

    @Override
    @Transactional
    public ManualSalesOrderView submit(CurrentAccessContext context, UUID draftId, long version, String idempotencyKey) {
        write(context);
        requireIdempotencyKey(idempotencyKey);
        ManualSalesOrderDraftModels.DraftView draft = persistence.getForUpdate(context, draftId);
        if (ManualSalesOrderDraftStatus.CREATED.name().equals(draft.status())) {
            return orderPersistence.findById(context.tenantId().toString(), context.workspaceId().toString(), draft.salesOrderId())
                    .orElseThrow(() -> new IllegalStateException("Created manual sales order is unavailable"));
        }
        if (draft.version() != version) throw new com.nexa.api.sales.application.exception.PurchaseRequestDraftConcurrencyException();
        ManualSalesOrderDraft.requireReady(ManualSalesOrderDraftStatus.valueOf(draft.status()));
        ManualSalesOrderDraftModels.ReviewView review = reviewForLockedDraft(draft);
        if (!review.readyToCreate()) throw new IllegalArgumentException("Manual sales order draft is not ready to create");
        ManualSalesOrderDraftModels.DeliveryView delivery = draft.delivery();
        CreateManualSalesOrderCommand command = new CreateManualSalesOrderCommand(
                draft.client().id(),
                delivery.addressId(),
                null,
                draft.requestedDeliveryDate(),
                delivery.deliveryNotes(),
                delivery.warehouseId(),
                delivery.routeProvider(),
                PaymentOption.from(draft.paymentPreference()),
                PurchaseRequestPriority.from(draft.priority()),
                draft.currency(),
                draft.notes(),
                draft.lines().stream().map(line -> new CreateManualSalesOrderCommand.Line(
                        UUID.fromString(line.skuId()), line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList());
        ManualSalesOrderView order = manualOrders.create(context, command, idempotencyKey);
        persistence.markCreated(context, draftId, version, order.id());
        return order;
    }

    @Override
    @Transactional
    public ManualSalesOrderDraftModels.DraftView abandon(CurrentAccessContext context, UUID draftId, long version) {
        write(context);
        return persistence.abandon(context, draftId, version);
    }

    private ManualSalesOrderDraftModels.ReviewView reviewForLockedDraft(ManualSalesOrderDraftModels.DraftView draft) {
        boolean client = draft.client() != null && "ACTIVE".equalsIgnoreCase(draft.client().status())
                && !"UNAVAILABLE".equalsIgnoreCase(draft.creditResult())
                && draft.requestedDeliveryDate() != null && draft.paymentPreference() != null && draft.priority() != null;
        boolean items = !draft.lines().isEmpty() && draft.lines().stream().allMatch(line -> "AVAILABLE".equalsIgnoreCase(line.availabilityStatus()));
        boolean delivery = draft.delivery() != null && draft.delivery().addressId() != null
                && draft.delivery().warehouseId() != null && draft.delivery().routeSnapshot() != null;
        List<String> missing = new java.util.ArrayList<>();
        if (!client) missing.add("client");
        if (!items) missing.add("items");
        if (!delivery) missing.add("delivery");
        return new ManualSalesOrderDraftModels.ReviewView(draft, client, items, delivery,
                client && items && delivery && ManualSalesOrderDraftStatus.READY_TO_CREATE.name().equals(draft.status()), missing);
    }

    private static void read(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Buyer surface cannot access Sales manual orders");
        context.requirePermission(Permission.SALES_READ);
    }

    private static void write(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Buyer surface cannot access Sales manual orders");
        context.requirePermission(Permission.SALES_WRITE);
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) throw new IdempotencyKeyRequiredException();
    }
}
