package com.nexa.api.customerbuyerrelationships.application.buyeraddress.service;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.BuyerAddressView;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.CreateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.UpdateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountAccessPolicy;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.domain.model.buyeraddress.BuyerAddress;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Buyer-owned locations. Client Account locations remain a separate shared company relation. */
public final class BuyerPersonalAddressService implements BuyerAddressUseCase {
    private final BuyerAddressPersistencePort persistence;
    private final CustomerAccountQuery accounts;

    public BuyerPersonalAddressService(BuyerAddressPersistencePort persistence, CustomerAccountQuery accounts) {
        this.persistence = Objects.requireNonNull(persistence, "Buyer address persistence is required");
        this.accounts = Objects.requireNonNull(accounts, "Customer account query is required");
    }

    @Override
    public List<BuyerAddressView> listMine(CurrentAccessContext context) {
        UUID buyerMembershipId = authorizeBuyer(context, false);
        requireActiveBuyerRelationship(context, buyerMembershipId, null);
        persistence.installMembershipScope(buyerMembershipId.toString());
        return persistence.list(tenant(context), workspace(context), buyerMembershipId.toString())
                .stream().map(BuyerPersonalAddressService::view).toList();
    }

    @Override
    @Transactional
    public BuyerAddressView createMine(CurrentAccessContext context, CreateBuyerAddressCommand command) {
        UUID buyerMembershipId = authorizeBuyer(context, true);
        requireActiveBuyerRelationship(context, buyerMembershipId, null);
        if (command == null) throw new IllegalArgumentException("Address command is required");
        persistence.installMembershipScope(buyerMembershipId.toString());
        BuyerAddress address = BuyerAddress.create(UUID.randomUUID(), context.tenantId().value(),
                context.workspaceId().value(), buyerMembershipId, command.label(), command.address());
        long now = System.currentTimeMillis();
        persistence.insert(address, now);
        if (command.defaultAddress() && persistence.setDefault(tenant(context), workspace(context),
                buyerMembershipId.toString(), address.id(), address.version(), now) == 0) {
            throw new CustomerRelationshipConflictException();
        }
        return detail(context, buyerMembershipId, address.id());
    }

    @Override
    @Transactional
    public BuyerAddressView updateMine(CurrentAccessContext context, UUID addressId,
                                       UpdateBuyerAddressCommand command, long expectedVersion) {
        UUID buyerMembershipId = authorizeBuyer(context, true);
        requireActiveBuyerRelationship(context, buyerMembershipId, null);
        Objects.requireNonNull(addressId, "Address id is required");
        if (command == null) throw new IllegalArgumentException("Address command is required");
        BuyerAddress proposed = BuyerAddress.rehydrate(addressId, context.tenantId().value(),
                context.workspaceId().value(), buyerMembershipId, command.label(), command.address(),
                false, true, expectedVersion);
        persistence.installMembershipScope(buyerMembershipId.toString());
        requireExisting(context, buyerMembershipId, addressId);
        if (persistence.update(tenant(context), workspace(context), buyerMembershipId.toString(), addressId,
                proposed.label(), proposed.address(), expectedVersion) == 0) {
            throw new CustomerRelationshipConflictException();
        }
        return detail(context, buyerMembershipId, addressId);
    }

    @Override
    @Transactional
    public BuyerAddressView setDefaultMine(CurrentAccessContext context, UUID addressId, long expectedVersion) {
        UUID buyerMembershipId = authorizeBuyer(context, true);
        requireActiveBuyerRelationship(context, buyerMembershipId, null);
        Objects.requireNonNull(addressId, "Address id is required");
        persistence.installMembershipScope(buyerMembershipId.toString());
        persistence.find(tenant(context), workspace(context), buyerMembershipId.toString(), addressId)
                .filter(BuyerAddress::active)
                .orElseThrow(() -> new ApiResourceNotFoundException("buyer-address"));
        if (persistence.setDefault(tenant(context), workspace(context), buyerMembershipId.toString(), addressId,
                expectedVersion, System.currentTimeMillis()) == 0) {
            throw new CustomerRelationshipConflictException();
        }
        return detail(context, buyerMembershipId, addressId);
    }

    @Override
    @Transactional
    public BuyerAddressView deactivateMine(CurrentAccessContext context, UUID addressId, long expectedVersion) {
        UUID buyerMembershipId = authorizeBuyer(context, true);
        requireActiveBuyerRelationship(context, buyerMembershipId, null);
        Objects.requireNonNull(addressId, "Address id is required");
        persistence.installMembershipScope(buyerMembershipId.toString());
        requireExisting(context, buyerMembershipId, addressId);
        if (persistence.deactivate(tenant(context), workspace(context), buyerMembershipId.toString(), addressId,
                expectedVersion, System.currentTimeMillis()) == 0) {
            throw new CustomerRelationshipConflictException();
        }
        return detail(context, buyerMembershipId, addressId);
    }

    @Override
    public List<BuyerAddressView> listForAccountBuyer(CurrentAccessContext context, String clientAccountId,
                                                       UUID buyerMembershipId) {
        authorizeAccountRead(context);
        if (clientAccountId == null || clientAccountId.isBlank()) {
            throw new ApiResourceNotFoundException("client-account");
        }
        Objects.requireNonNull(buyerMembershipId, "Buyer membership id is required");
        String accountId = resolveReadableAccount(context, clientAccountId.trim());
        requireActiveBuyerRelationship(context, buyerMembershipId, accountId);
        persistence.installMembershipScope(buyerMembershipId.toString());
        return persistence.list(tenant(context), workspace(context), buyerMembershipId.toString())
                .stream().map(BuyerPersonalAddressService::view).toList();
    }

    private UUID authorizeBuyer(CurrentAccessContext context, boolean write) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer membership is required to manage personal addresses");
        }
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(write ? Permission.SALES_BUYER_WRITE : Permission.SALES_BUYER_READ);
        return context.membershipId().value();
    }

    private void authorizeAccountRead(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified workforce access context is required");
        context.requireSurface(Surface.PLATFORM);
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            ClientAccountAccessPolicy.requireRead(context);
            return;
        }
        if (!context.hasRole(MembershipRole.SALES) || context.hasRole(MembershipRole.COMPANY_OWNER)) {
            throw new AccessPolicyViolation("Personal Buyer address audit is restricted to assigned Sales and BOM");
        }
        ClientAccountAccessPolicy.requireRead(context);
    }

    private String resolveReadableAccount(CurrentAccessContext context, String requestedAccountId) {
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            return accounts.findReference(tenant(context), workspace(context), requestedAccountId)
                    .map(CustomerAccountReference::id)
                    .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
        }
        return accounts.findSalesOwnerReference(tenant(context), workspace(context), requestedAccountId,
                        context.membershipId().toString())
                .map(CustomerAccountReference::id)
                .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
    }

    private void requireActiveBuyerRelationship(CurrentAccessContext context, UUID buyerMembershipId,
                                                String expectedAccountId) {
        CustomerAccountReference reference = accounts.findBuyerReference(tenant(context), workspace(context),
                        buyerMembershipId.toString())
                .filter(CustomerAccountReference::active)
                .filter(value -> expectedAccountId == null || expectedAccountId.equals(value.id()))
                .orElseThrow(() -> expectedAccountId == null
                        ? new AccessPolicyViolation("An active Buyer account relationship is required")
                        : new ApiResourceNotFoundException("client-account"));
        Objects.requireNonNull(reference);
    }

    private BuyerAddressView detail(CurrentAccessContext context, UUID buyerMembershipId, UUID addressId) {
        return persistence.find(tenant(context), workspace(context), buyerMembershipId.toString(), addressId)
                .map(BuyerPersonalAddressService::view)
                .orElseThrow(() -> new ApiResourceNotFoundException("buyer-address"));
    }

    private void requireExisting(CurrentAccessContext context, UUID buyerMembershipId, UUID addressId) {
        persistence.find(tenant(context), workspace(context), buyerMembershipId.toString(), addressId)
                .orElseThrow(() -> new ApiResourceNotFoundException("buyer-address"));
    }

    private static BuyerAddressView view(BuyerAddress address) {
        return new BuyerAddressView(address.id(), address.label(), address.address(), address.defaultAddress(),
                address.active(), address.version());
    }

    private static String tenant(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
}
