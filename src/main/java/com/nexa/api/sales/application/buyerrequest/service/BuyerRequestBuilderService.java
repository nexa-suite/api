package com.nexa.api.sales.application.buyerrequest.service;

import com.nexa.api.sales.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand;
import com.nexa.api.sales.application.buyerrequest.port.BuyerRequestBuilderUseCase;
import com.nexa.api.sales.application.buyerrequest.port.BuyerRequestPersistencePort;
import com.nexa.api.sales.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequest;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

public class BuyerRequestBuilderService implements BuyerRequestBuilderUseCase {
    private final SalesSnapshotAssembler snapshots;
    private final BuyerRequestPersistencePort persistence;

    public BuyerRequestBuilderService(SalesSnapshotAssembler snapshots, BuyerRequestPersistencePort persistence) {
        this.snapshots = snapshots;
        this.persistence = persistence;
    }

    @Override
    public BuyerRequestSnapshot preview(CurrentAccessContext context, CreateBuyerRequestCommand command) {
        buyerWrite(context);
        if (command == null) throw new IllegalArgumentException("Buyer request command is required");
        return snapshots.buyer(context, command.clientAccountId(), command.addressId(), command.manualAddress(),
                command.requestedDeliveryDate(), command.deliveryNotes(), command.comments(), command.warehouseId(), command.routeProvider(),
                command.paymentOption(), command.lines()).snapshot();
    }

    @Override
    @Transactional
    public BuyerRequestView create(CurrentAccessContext context, CreateBuyerRequestCommand command) {
        buyerWrite(context);
        if (command == null) throw new IllegalArgumentException("Buyer request command is required");
        var assembled = snapshots.buyer(context, command.clientAccountId(), command.addressId(), command.manualAddress(),
                command.requestedDeliveryDate(), command.deliveryNotes(), command.comments(), command.warehouseId(), command.routeProvider(),
                command.paymentOption(), command.lines());
        String idValue = UUID.randomUUID().toString();
        BuyerRequest request = BuyerRequest.draft(new PurchaseRequestId(idValue),
                assembled.snapshot().commercial().clientAccountId(),
                new BuyerMembershipId(context.membershipId().value()), assembled.lines(), assembled.snapshot());
        String code = "PR-" + idValue.substring(0, 8).toUpperCase(Locale.ROOT);
        return persistence.save(request, context.tenantId().toString(), context.workspaceId().toString(),
                code, System.currentTimeMillis());
    }

    private static void buyerWrite(CurrentAccessContext context) {
        if (!context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Buyer Request Builder is buyer-only");
        context.requirePermission(Permission.SALES_BUYER_WRITE);
    }
}
