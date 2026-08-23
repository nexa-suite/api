package com.nexa.api.customerrelationships.application.clientaccount.service;

import com.nexa.api.customerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.customerrelationships.domain.model.clientaccount.*;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

public class ClientAccountService implements ClientAccountUseCase {
	private final ClientAccountPersistencePort persistence;
	private final BuyerMembershipDirectory buyerMemberships;
	public ClientAccountService(ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyerMemberships) {
		this.persistence = persistence;
		this.buyerMemberships = buyerMemberships;
	}

	@Override public CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size) {
		internal(context, Permission.SALES_READ); return persistence.list(scope(context), workspace(context), search, status, page, size);
	}
	@Override public ClientAccountView detail(CurrentAccessContext context, String id) {
		internal(context, Permission.SALES_READ); return persistence.find(scope(context), workspace(context), id).orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
	}
	@Override public ClientAccountView buyerDetail(CurrentAccessContext context) {
		context.requirePermission(Permission.SALES_BUYER_READ);
		return persistence.findForBuyer(scope(context), workspace(context), context.membershipId().toString())
				.orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
	}
	@Override public List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context) {
		internal(context, Permission.SALES_READ);
		return buyerMemberships.findActiveBuyers(scope(context), workspace(context)).stream()
				.filter(candidate -> !persistence.isBuyerMembershipAssigned(scope(context), workspace(context), candidate.id()))
				.map(candidate -> new BuyerMembershipCandidate(candidate.id(), candidate.email(), candidate.displayName()))
				.toList();
	}
	@Override @Transactional public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) {
		internal(context, Permission.SALES_WRITE); validateDomain(command);
		UUID id = UUID.randomUUID(); persistence.insert(command, scope(context), workspace(context), id, now());
		return detail(context, id.toString());
	}
	@Override @Transactional public ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version) {
		internal(context, Permission.SALES_WRITE);
		int changed = persistence.update(scope(context), workspace(context), id, command.businessName(), command.commercialName(), command.contactPerson(), command.contactEmail(), command.phone(), command.deliveryProfile(), command.paymentCondition(), version);
		if (changed == 0) throw new CustomerRelationshipConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version) {
		internal(context, Permission.SALES_WRITE);
		if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw new ApiResourceNotFoundException("client-account-status");
		if (persistence.updateStatus(scope(context), workspace(context), id, status, version) == 0) throw new CustomerRelationshipConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version) {
		internal(context, Permission.SALES_WRITE);
		ClientAccountView account = detail(context, id);
		if (account.version() != version) throw new CustomerRelationshipConflictException();
		if (account.buyerMembershipId() != null) throw new CustomerRelationshipConflictException();
		if (buyerMemberships.findActiveBuyer(scope(context), workspace(context), membershipId).isEmpty()
				|| persistence.isBuyerMembershipAssigned(scope(context), workspace(context), membershipId)) {
			throw new ApiResourceNotFoundException("buyer-membership");
		}
		if (persistence.associateBuyer(scope(context), workspace(context), account.id(), membershipId, UUID.randomUUID(), now(), version) == 0) throw new CustomerRelationshipConflictException();
		return detail(context, id);
	}

	private static void validateDomain(ClientAccountView command) {
		new ClientCode(command.code()); new TaxIdentifier(command.countryCode(), command.taxType(), command.taxValue());
		new BusinessName(command.businessName()); new CommercialName(command.commercialName()); new ContactEmail(command.contactEmail());
		new PhoneNumber(command.phone()); new PaymentCondition(command.paymentCondition());
	}
	private static void internal(CurrentAccessContext context, Permission permission) { if (context.hasRole(com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole.BUYER)) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Administrative sales access is not available to buyers"); context.requirePermission(permission); }
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
}
