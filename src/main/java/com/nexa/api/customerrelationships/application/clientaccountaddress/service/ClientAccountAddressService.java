package com.nexa.api.customerrelationships.application.clientaccountaddress.service;

import com.nexa.api.customerrelationships.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.customerrelationships.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.customerrelationships.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.customerrelationships.domain.model.clientaccount.ClientAccountAddress;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public class ClientAccountAddressService implements ClientAccountAddressUseCase {
    private final ClientAccountAddressPersistencePort persistence;
    private final CustomerAccountQuery accounts;

    public ClientAccountAddressService(ClientAccountAddressPersistencePort persistence,
                                       CustomerAccountQuery accounts) {
        this.persistence = persistence;
        this.accounts = accounts;
    }

    @Override
    public List<ClientAccountAddressView> list(CurrentAccessContext context, String clientAccountId) {
        String accountId = scopedAccount(context, clientAccountId, false);
        return persistence.list(scope(context), workspace(context), accountId).stream().map(ClientAccountAddressService::view).toList();
    }

    @Override
    @Transactional
    public ClientAccountAddressView create(CurrentAccessContext context, String clientAccountId,
                                           CreateClientAccountAddressCommand command) {
        String accountId = scopedAccount(context, clientAccountId, true);
        if (command == null) throw new IllegalArgumentException("Address command is required");
        ClientAccountAddress address = ClientAccountAddress.create(UUID.randomUUID(), context.tenantId().value(),
                context.workspaceId().value(), accountId, command.label(), command.address(), command.defaultAddress());
        persistence.insert(address.withDefault(false, 0), now());
        if (command.defaultAddress() && persistence.setDefault(scope(context), workspace(context), accountId,
                address.id().toString(), 0, now()) == 0) throw new CustomerRelationshipConflictException();
        return view(persistence.find(scope(context), workspace(context), accountId, address.id().toString()).orElse(address));
    }

    @Override
    @Transactional
    public ClientAccountAddressView update(CurrentAccessContext context, String clientAccountId, String addressId,
                                           UpdateClientAccountAddressCommand command, long expectedVersion) {
        String accountId = scopedAccount(context, clientAccountId, true);
        if (command == null) throw new IllegalArgumentException("Address command is required");
        if (persistence.update(scope(context), workspace(context), accountId, addressId, command.label(),
                command.address(), expectedVersion) == 0) throw new CustomerRelationshipConflictException();
        return detail(context, accountId, addressId);
    }

    @Override
    @Transactional
    public ClientAccountAddressView setDefault(CurrentAccessContext context, String clientAccountId,
                                               String addressId, long expectedVersion) {
        String accountId = scopedAccount(context, clientAccountId, true);
        if (persistence.find(scope(context), workspace(context), accountId, addressId).isEmpty()) {
            throw new ApiResourceNotFoundException("client-account-address");
        }
        if (persistence.setDefault(scope(context), workspace(context), accountId, addressId, expectedVersion, now()) == 0) {
            throw new CustomerRelationshipConflictException();
        }
        return detail(context, accountId, addressId);
    }

    @Override
    @Transactional
    public ClientAccountAddressView deactivate(CurrentAccessContext context, String clientAccountId,
                                               String addressId, long expectedVersion) {
        String accountId = scopedAccount(context, clientAccountId, true);
        if (persistence.deactivate(scope(context), workspace(context), accountId, addressId,
                expectedVersion, now()) == 0) throw new CustomerRelationshipConflictException();
        return detail(context, accountId, addressId);
    }

    private ClientAccountAddressView detail(CurrentAccessContext context, String accountId, String addressId) {
        return persistence.find(scope(context), workspace(context), accountId, addressId)
                .map(ClientAccountAddressService::view)
                .orElseThrow(() -> new ApiResourceNotFoundException("client-account-address"));
    }

    private String scopedAccount(CurrentAccessContext context, String requestedAccountId, boolean write) {
        if (context.hasRole(MembershipRole.BUYER)) {
            if (write) context.requirePermission(Permission.SALES_BUYER_WRITE);
            else context.requirePermission(Permission.SALES_BUYER_READ);
            return accounts.findBuyerReference(scope(context), workspace(context), context.membershipId().toString())
                    .map(CustomerAccountReference::id)
                    .filter(id -> requestedAccountId == null || id.equals(requestedAccountId.trim()))
                    .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
        }
        if (write) context.requirePermission(Permission.SALES_WRITE); else context.requirePermission(Permission.SALES_READ);
        if (requestedAccountId == null || requestedAccountId.isBlank()) throw new ApiResourceNotFoundException("client-account");
        return accounts.findReference(scope(context), workspace(context), requestedAccountId.trim())
                .map(CustomerAccountReference::id)
                .orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
    }

    private static ClientAccountAddressView view(ClientAccountAddress address) {
        return new ClientAccountAddressView(address.id(), address.clientAccountId(), address.label(), address.address(),
                address.defaultAddress(), address.active(), address.version());
    }
    private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
    private static long now() { return System.currentTimeMillis(); }
}
