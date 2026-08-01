package com.nexa.api.sales.application.salesorder.service;

import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.exception.PurchaseRequestAlreadyConvertedException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Application orchestration for the approved Purchase Request conversion.
 * SQL adapters only load/persist rows through the conversion port.
 */
public final class ConvertApprovedPurchaseRequestToSalesOrderService {
    private final SalesOrderConversionPersistencePort persistence;

    public ConvertApprovedPurchaseRequestToSalesOrderService(SalesOrderConversionPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Transactional
    public SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId,
                                  long purchaseRequestVersion, String idempotencyKey, String note) {
        requireCommercialWrite(context);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new IdempotencyKeyRequiredException();
        }
        if (persistence == null) {
            throw new IllegalStateException("Sales order conversion persistence is not configured");
        }
        if (note != null && note.length() > 2000) {
            throw new com.nexa.api.sales.domain.exception.SalesInvariantViolation("Conversion note is too long");
        }

        String tenant = context.tenantId().toString();
        String workspace = context.workspaceId().toString();
        String actor = context.membershipId().toString();
        String requestHash = requestHash(purchaseRequestId, purchaseRequestVersion, note);
        var prior = persistence.findByIdempotency(tenant, workspace, actor, idempotencyKey, requestHash);
        if (prior.isPresent()) return prior.get();

        // The adapter locks and rehydrates the approved snapshot inside the
        // transaction. The application still owns the ordering and decisions.
        var snapshot = persistence.loadApprovedSnapshot(tenant, workspace, purchaseRequestId, purchaseRequestVersion);
        if (snapshot.isEmpty()) {
            // A concurrent request can have completed while this request waited
            // on the Purchase Request row lock. Replay wins before conflict.
            var replay = persistence.findByIdempotency(tenant, workspace, actor, idempotencyKey, requestHash);
            if (replay.isPresent()) return replay.get();
            if (persistence.findBySourcePurchaseRequest(tenant, workspace, purchaseRequestId).isPresent()) {
                throw new PurchaseRequestAlreadyConvertedException();
            }
            throw new SalesConcurrencyConflictException();
        }

        var identity = persistence.nextIdentity(tenant, workspace);
        SalesOrder aggregate = SalesOrder.fromApprovedSnapshot(snapshot.get(), identity.id(), identity.number(),
                new MembershipId(context.membershipId().value()),
                java.time.Instant.ofEpochMilli(System.currentTimeMillis()));
        return persistence.persistConversion(aggregate, purchaseRequestVersion, actor, idempotencyKey, note,
                System.currentTimeMillis(), requestHash);
    }

    private static void requireCommercialWrite(CurrentAccessContext context) {
        if (context.role() != MembershipRole.SALES && context.role() != MembershipRole.COMPANY_OWNER) {
            throw new AccessPolicyViolation("Commercial sales access is required");
        }
        context.requirePermission(Permission.SALES_WRITE);
    }

    private static String requestHash(String purchaseRequestId, long version, String note) {
        String canonical = purchaseRequestId.trim() + "|" + version + "|" + (note == null ? "<null>" : note.trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
