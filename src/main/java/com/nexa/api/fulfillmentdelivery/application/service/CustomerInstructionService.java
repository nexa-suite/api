package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.model.CustomerInstructionModels.*;
import com.nexa.api.fulfillmentdelivery.application.port.CustomerInstructionPort;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Service
@Profile("!test")
public class CustomerInstructionService {
    private final CustomerInstructionPort instructions;
    private final SalesOrderFulfillmentQuery orders;
    private final CustomerAccountQuery customers;
    private final Clock clock;
    public CustomerInstructionService(CustomerInstructionPort instructions, SalesOrderFulfillmentQuery orders,
                                      CustomerAccountQuery customers, Clock clock) {
        this.instructions = instructions; this.orders = orders; this.customers = customers; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Snapshot read(CurrentAccessContext c, UUID order, boolean buyer) {
        var currentOrder = authorize(c, order, buyer, false);
        Snapshot result = instructions.read(c.tenantId().value(), c.workspaceId().value(), order);
        boolean active = !Set.of("CANCELLED", "REJECTED", "COMPLETED").contains(currentOrder.status());
        return new Snapshot(result.salesOrderId(),result.version(),active && result.editable(),result.instructions());
    }
    @Transactional
    public Snapshot publish(CurrentAccessContext c, UUID order, boolean buyer, long version,
                            UUID instructionId, DeliveryInstructionKind kind, String content,
                            String sourceReference, String key) {
        var currentOrder = authorize(c, order, buyer, true);
        if (version < 0 || kind == null || content == null || content.isBlank() || content.length() > 2000
                || key == null || key.isBlank() || key.length() > 160) throw invalid("INVALID_REQUEST");
        if (!buyer && (sourceReference == null || sourceReference.isBlank() || sourceReference.length() > 500))
            throw invalid("CUSTOMER_INSTRUCTION_SOURCE_REQUIRED");
        String source = buyer ? "BUYER" : "CUSTOMER_REPORTED_BY_SALES";
        String reference = buyer ? null : sourceReference.trim();
        String text = content.trim();
        String hash = hash(order+"|"+version+"|"+instructionId+"|"+kind+"|"+text.length()+":"+text+"|"+source+"|"+Objects.toString(reference,""));
        Snapshot result = instructions.publish(new Publish(c.tenantId().value(), c.workspaceId().value(), order,
                c.membershipId().value(), instructionId, version, kind, text, source, reference, key, hash, clock.instant(), isActive(currentOrder)));
        return new Snapshot(result.salesOrderId(), result.version(), isActive(currentOrder) && result.editable(), result.instructions());
    }
    private SalesOrderFulfillmentQuery.Snapshot authorize(CurrentAccessContext c, UUID orderId, boolean buyer, boolean write) {
        if (buyer) {
            if (!c.hasRole(MembershipRole.BUYER)) throw invalid("FORBIDDEN");
            c.requirePermission(write ? PermissionKey.BUYER_SALES_WRITE : PermissionKey.BUYER_ORDER_READ);
        } else {
            if (!c.hasRole(MembershipRole.SALES)) throw invalid("FORBIDDEN");
            c.requirePermission(write ? PermissionKey.CLIENT_MANAGE : PermissionKey.SALES_ORDER_READ);
        }
        var order = write ? orders.getForUpdate(c.tenantId().value(), c.workspaceId().value(), orderId)
                          : orders.get(c.tenantId().value(), c.workspaceId().value(), orderId);
        UUID account = order.clientAccountId();
        if (account == null || customers.findReference(c.tenantId().toString(), c.workspaceId().toString(), account.toString()).isEmpty()
                || buyer && !customers.hasBuyerRelationship(c.tenantId().toString(), c.workspaceId().toString(), c.membershipId().toString(), account.toString()))
            throw new FulfillmentOperationException("DELIVERY_NOT_FOUND", true);
        return order;
    }
    private static boolean isActive(SalesOrderFulfillmentQuery.Snapshot order) {
        return !Set.of("CANCELLED", "REJECTED", "COMPLETED").contains(order.status());
    }
    private static FulfillmentOperationException invalid(String code) { return new FulfillmentOperationException(code, false); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
