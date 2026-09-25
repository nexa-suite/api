package com.nexa.api.salescommitment.application.directorder.service;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
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
    private final CustomerAccountQuery customerAccounts;

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock) {
        this(commitments, orders, clock, null, new ObjectMapper(), null, null);
    }

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock,
                              IdempotencyPersistencePort idempotency, ObjectMapper objectMapper) {
        this(commitments, orders, clock, idempotency, objectMapper, null, null);
    }

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock,
                              IdempotencyPersistencePort idempotency, ObjectMapper objectMapper, SellableSkuQuery sellableSkus) {
        this(commitments, orders, clock, idempotency, objectMapper, sellableSkus, null);
    }

    public DirectOrderService(CommercialCommitmentPort commitments, SalesOrderPersistencePort orders, Clock clock,
                              IdempotencyPersistencePort idempotency, ObjectMapper objectMapper,
                              SellableSkuQuery sellableSkus, CustomerAccountQuery customerAccounts) {
        this.commitments = commitments;
        this.orders = orders;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sellableSkus = sellableSkus;
        this.customerAccounts = customerAccounts;
    }

    @Override
    @Transactional
    public SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
                                 java.time.LocalDate requestedDeliveryDate, String deliverySnapshot,
                                 String paymentOption, String notes, List<Line> lines, String idempotencyKey) {
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Direct Order requires a Customer Buyer");
        }
        context.requirePermission(Permission.SALES_BUYER_WRITE);
        if (lines == null || lines.isEmpty()) {
            throw new CommercialBusinessException("VALIDATION_ERROR");
        }
        if (customerAccounts == null) throw new IllegalStateException("Buyer relationship lookup is not configured");
        String tenantId = context.tenantId().toString();
        String workspaceId = context.workspaceId().toString();
        String buyerMembershipId = context.membershipId().toString();
        java.util.UUID clientAccount = customerAccounts.findBuyerReference(tenantId, workspaceId, buyerMembershipId)
                .filter(com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference::active)
                .map(reference -> {
                    try { return java.util.UUID.fromString(reference.id()); }
                    catch (IllegalArgumentException exception) { throw new CommercialBusinessException("CLIENT_ACCOUNT_NOT_FOUND"); }
                })
                .orElseThrow(() -> new CommercialBusinessException("CLIENT_ACCOUNT_NOT_FOUND"));
        if (clientAccountId != null && !clientAccountId.isBlank() && !clientAccount.toString().equals(clientAccountId.trim())) {
            throw new CommercialBusinessException("CLIENT_ACCOUNT_NOT_FOUND");
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
