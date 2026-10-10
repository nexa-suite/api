package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryRecord;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.EventView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.Page;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingQueryPort;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Buyer-only read boundary for actual BC-06 Delivery records. */
@Service
@Profile("!test")
public class BuyerDeliveryTrackingService {
    private final BuyerDeliveryTrackingQueryPort deliveries;
    private final CustomerAccountQuery customerAccounts;
    private final SalesOrderFulfillmentQuery salesOrders;

    public BuyerDeliveryTrackingService(BuyerDeliveryTrackingQueryPort deliveries,
                                        CustomerAccountQuery customerAccounts,
                                        SalesOrderFulfillmentQuery salesOrders) {
        this.deliveries = Objects.requireNonNull(deliveries, "Buyer delivery query is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts, "Customer account query is required");
        this.salesOrders = Objects.requireNonNull(salesOrders, "Sales order query is required");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<DeliveryView> list(CurrentAccessContext context, int page, int size) {
        Scope scope = scope(context);
        if (page < 0 || size < 1 || size > 100) throw error("INVALID_REQUEST", false);
        Page<DeliveryRecord> result = deliveries.list(scope.tenantId(), scope.workspaceId(),
                scope.clientAccountId(), page, size);
        return new Page<>(result.items().stream().map(BuyerDeliveryTrackingService::buyerSafe).toList(),
                result.page(), result.size(), result.total());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DeliveryView detail(CurrentAccessContext context, UUID deliveryId) {
        Scope scope = scope(context);
        DeliveryRecord record = requireDelivery(scope, deliveryId);
        return buyerSafe(record);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<EventView> events(CurrentAccessContext context, UUID deliveryId) {
        Scope scope = scope(context);
        requireDelivery(scope, deliveryId);
        return deliveries.events(scope.tenantId(), scope.workspaceId(), deliveryId);
    }

    private DeliveryRecord requireDelivery(Scope scope, UUID deliveryId) {
        if (deliveryId == null) throw error("RESOURCE_NOT_FOUND", true);
        DeliveryRecord record = deliveries.detail(scope.tenantId(), scope.workspaceId(), deliveryId);
        if (record == null) throw error("RESOURCE_NOT_FOUND", true);
        Map<UUID, SalesOrderFulfillmentQuery.Header> headers = salesOrders.findHeaders(scope.tenantId(),
                scope.workspaceId(), List.of(record.salesOrderId()));
        SalesOrderFulfillmentQuery.Header order = headers.get(record.salesOrderId());
        if (order == null || !scope.clientAccountId().equals(order.clientAccountId())) {
            throw error("RESOURCE_NOT_FOUND", true);
        }
        return new DeliveryRecord(record.id(), record.salesOrderId(), order.number(), record.status(),
                record.destination(), record.scheduledAt(), record.dispatchedAt(), record.deliveredAt(),
                record.proofOfDeliveryStatus(), record.version(), record.createdAt(), record.updatedAt());
    }

    private Scope scope(CurrentAccessContext context) {
        context.requirePermission(PermissionKey.BUYER_TRACKING_READ);
        String tenant = context.tenantId().toString();
        String workspace = context.workspaceId().toString();
        String membership = context.membershipId().toString();
        CustomerAccountReference reference = customerAccounts.findBuyerReference(tenant, workspace, membership)
                .filter(CustomerAccountReference::active)
                .orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
        try {
            return new Scope(UUID.fromString(tenant), UUID.fromString(workspace),
                    UUID.fromString(reference.id()));
        } catch (IllegalArgumentException exception) {
            throw error("RESOURCE_NOT_FOUND", true);
        }
    }

    private static DeliveryView buyerSafe(DeliveryRecord value) {
        return new DeliveryView(value.id().toString(), value.salesOrderNumber(), value.status(), value.destination(),
                value.scheduledAt(), value.dispatchedAt(), value.deliveredAt(), value.proofOfDeliveryStatus(),
                value.version(), value.createdAt(), value.updatedAt());
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private record Scope(UUID tenantId, UUID workspaceId, UUID clientAccountId) { }
}
