package com.nexa.api.sales.application.directorder.service;

import com.nexa.api.catalogmanagement.application.publicapi.SellableSkuQuery;
import com.nexa.api.sales.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.sales.application.exception.CommercialBusinessException;
import com.nexa.api.sales.application.port.CommercialCommitmentPort;
import com.nexa.api.sales.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

public class DirectOrderService implements DirectOrderUseCase {
    private final CommercialCommitmentPort commitments;
    private final SalesOrderPersistencePort orders;
    private final Clock clock;
    private final IdempotencyPersistencePort idempotency;
    private final ObjectMapper objectMapper;
    private final SellableSkuQuery sellableSkus;

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock) {
        this(commitments, orders, clock, null, new ObjectMapper(), null);
    }

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock,
                              IdempotencyPersistencePort idempotency, ObjectMapper objectMapper) {
        this(commitments, orders, clock, idempotency, objectMapper, null);
    }

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock,
                              IdempotencyPersistencePort idempotency, ObjectMapper objectMapper, SellableSkuQuery sellableSkus) {
        this.commitments = commitments;
        this.orders = orders;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sellableSkus = sellableSkus;
    }

    @Override
    @Transactional
    public SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
                                 java.time.LocalDate requestedDeliveryDate, String deliverySnapshot,
                                 String paymentOption, String notes, List<Line> lines, String idempotencyKey) {
        if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Direct Order is internal sales-only");
        context.requirePermission(Permission.SALES_WRITE);
        if (clientAccountId == null || clientAccountId.isBlank() || lines == null || lines.isEmpty()) {
            throw new CommercialBusinessException("VALIDATION_ERROR");
        }
        java.util.UUID clientAccount;
        try {
            clientAccount = java.util.UUID.fromString(clientAccountId.trim());
        } catch (IllegalArgumentException exception) {
            throw new CommercialBusinessException("VALIDATION_ERROR");
        }
        PaymentOption payment = PaymentOption.from(paymentOption);
        if (payment == null) throw new CommercialBusinessException("VALIDATION_ERROR");
        String normalizedPriority = PurchaseRequestPriority.from(priority).name();
        List<Line> canonicalLines = lines.stream().map(line -> canonicalLine(context.tenantId().value(), context.workspaceId().value(), line)).toList();
        String hash = hash(clientAccount.toString(), normalizedPriority, requestedDeliveryDate, deliverySnapshot, payment.name(), notes, canonicalLines);
        if (idempotency != null) {
            String tenant = context.tenantId().toString();
            String workspace = context.workspaceId().toString();
            String actor = context.membershipId().toString();
            idempotency.lock(tenant, workspace, actor, "direct-order", idempotencyKey);
            var prior = idempotency.find(tenant, workspace, actor, "direct-order", idempotencyKey, hash);
            if (prior.isPresent() && prior.get().responseJson() != null && !prior.get().responseJson().isBlank()) {
                try { return objectMapper.readValue(prior.get().responseJson(), SalesOrderView.class); }
                catch (Exception exception) { throw new IllegalStateException("Direct Order idempotency snapshot is invalid", exception); }
            }
        }
        CommercialCommitmentPort.DirectOrderResult result = commitments.establishDirectOrder(
                new CommercialCommitmentPort.DirectOrderCommand(context.tenantId().value(), context.workspaceId().value(),
                        clientAccount, context.membershipId().value(), context.membershipId().value(),
                        normalizedPriority, requestedDeliveryDate,
                        deliverySnapshot, payment.name(), notes,
                        clock.instant(), canonicalLines.stream().map(line -> new CommercialCommitmentPort.DirectOrderLine(line.catalogItemId(), line.quantity(), line.unit())).toList(),
                        idempotencyKey, hash));
        SalesOrderView response = orders.find(context.tenantId().toString(), context.workspaceId().toString(), null, result.salesOrderId().toString())
                .orElseThrow(() -> new IllegalStateException("Direct Order result is unavailable"));
        if (idempotency != null) {
            try {
                idempotency.updateResponse(context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString(),
                        "direct-order", idempotencyKey, objectMapper.writeValueAsString(response));
            } catch (Exception exception) {
                throw new IllegalStateException("Direct Order idempotency snapshot could not be serialized", exception);
            }
        }
        return response;
    }

    private static String hash(String clientAccountId, String priority, java.time.LocalDate deliveryDate,
                               String deliverySnapshot, String paymentOption, String notes, List<Line> lines) {
        String canonical = clientAccountId.trim() + "|" + priority + "|" + value(deliveryDate) + "|" + value(deliverySnapshot) + "|" + paymentOption + "|" + value(notes)
                + "|" + lines.stream().map(line -> value(line.catalogItemId()).trim() + ":" + line.quantity() + ":" + normalizedUnit(line.unit())).sorted().collect(java.util.stream.Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
    private static String normalizedUnit(String value) { return value == null || value.isBlank() ? "" : value.trim(); }
    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private Line canonicalLine(java.util.UUID tenantId, java.util.UUID workspaceId, Line line) {
        String catalogItemId = value(line == null ? null : line.catalogItemId()).trim();
        String unit = normalizedUnit(line == null ? null : line.unit());
        if (unit.isBlank() && sellableSkus != null && !catalogItemId.isBlank()) {
            unit = sellableSkus.findActiveByLegacyCatalogItemId(tenantId, workspaceId, catalogItemId)
                    .map(SellableSkuQuery.SellableSkuReference::unitOfMeasure).orElse("");
        }
        return line == null ? new Line(catalogItemId, null, unit) : new Line(catalogItemId, line.quantity(), unit);
    }
}
