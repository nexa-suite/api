package com.nexa.api.customerbuyerrelationships.application.clientaccount.service;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountRegistrationResult;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.*;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import java.time.Instant;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public class ClientAccountService implements ClientAccountUseCase {
	private final ClientAccountPersistencePort persistence;
	private final BuyerMembershipDirectory buyerMemberships;
	private final ActiveSalesMembershipDirectory salesMemberships;
	private final BusinessTraceabilityCommands traceability;
	public ClientAccountService(ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyerMemberships) {
		this(persistence, buyerMemberships, (tenant, workspace, membership) -> Optional.empty(), request -> { });
	}
	public ClientAccountService(ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyerMemberships,
			ActiveSalesMembershipDirectory salesMemberships, BusinessTraceabilityCommands traceability) {
		this.persistence = Objects.requireNonNull(persistence);
		this.buyerMemberships = Objects.requireNonNull(buyerMemberships);
		this.salesMemberships = Objects.requireNonNull(salesMemberships);
		this.traceability = Objects.requireNonNull(traceability);
	}

	@Override public CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size) {
		ClientAccountAccessPolicy.requireRead(context);
		if (ClientAccountAccessPolicy.isSalesOwnerScoped(context)) {
			return persistence.listForSalesOwner(scope(context), workspace(context), context.membershipId().toString(),
					search, status, page, size);
		}
		return persistence.list(scope(context), workspace(context), search, status, page, size);
	}
	@Override public ClientAccountView detail(CurrentAccessContext context, String id) {
		ClientAccountAccessPolicy.requireRead(context);
		return accountForReader(context, id).orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
	}
	@Override public ClientAccountView buyerDetail(CurrentAccessContext context) {
		if (!context.hasRole(MembershipRole.BUYER)) {
			throw new AccessPolicyViolation("Buyer self-service is only available to the authenticated Buyer membership");
		}
		context.requireSurface(Surface.PORTAL);
		context.requirePermission(Permission.SALES_BUYER_READ);
		return persistence.findForBuyer(scope(context), workspace(context), context.membershipId().toString())
				.orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
	}
	@Override @Transactional public ClientAccountRegistrationResult registerOwnBusiness(CurrentAccessContext context,
			BuyerClientAccountRegistrationCommand registration) {
		if (!context.hasRole(MembershipRole.BUYER)) {
			throw new AccessPolicyViolation(
					"Buyer account registration is only available to the authenticated Buyer membership");
		}
		context.requireSurface(Surface.PORTAL);
		context.requirePermission(Permission.SALES_BUYER_WRITE);
		String membershipId = context.membershipId().toString();
		BuyerMembershipDirectory.BuyerMembershipReference identity = buyerMemberships
				.findActiveBuyer(scope(context), workspace(context), membershipId)
				.orElseThrow(() -> new ApiResourceNotFoundException("buyer-membership"));
		ClientAccountView command = buyerAccount(registration, identity, membershipId);
		if (persistence.isBuyerMembershipAssigned(scope(context), workspace(context), membershipId)) {
			Optional<ClientAccountView> existing = persistence.findForBuyer(
					scope(context), workspace(context), membershipId);
			if (existing.isPresent() && persistence.isExactBuyerRegistration(
					scope(context), workspace(context), membershipId, command)) {
				return new ClientAccountRegistrationResult(existing.orElseThrow(), false);
			}
			throw new CustomerRelationshipConflictException();
		}
		UUID id = UUID.randomUUID();
		persistence.insert(command, scope(context), workspace(context), id, null, now());
		if (persistence.associateBuyer(scope(context), workspace(context), id.toString(), membershipId,
				UUID.randomUUID(), now(), 0) == 0) throw new CustomerRelationshipConflictException();
		ClientAccountView created = persistence.findForBuyer(scope(context), workspace(context), membershipId)
				.orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
		return new ClientAccountRegistrationResult(created, true);
	}
	@Override public List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context) {
		ClientAccountAccessPolicy.requireBuyerCandidateRead(context);
		return buyerMemberships.findActiveBuyers(scope(context), workspace(context)).stream()
				.filter(candidate -> !persistence.isBuyerMembershipAssigned(scope(context), workspace(context), candidate.id()))
				.map(candidate -> new BuyerMembershipCandidate(candidate.id(), candidate.email(), candidate.displayName()))
				.toList();
	}
	@Override @Transactional public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
		if (command != null && command.accountType() != ClientAccountType.BUSINESS) {
			throw new AccessPolicyViolation("Individual Buyer accounts can only be registered by their authenticated Buyer");
		}
		validateDomain(command);
		UUID id = UUID.randomUUID();
		UUID salesOwner = context.hasRole(MembershipRole.SALES) ? context.membershipId().value() : null;
		persistence.insert(command, scope(context), workspace(context), id, salesOwner, now());
		return detail(context, id.toString());
	}
	@Override @Transactional public ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
		detail(context, id);
		int changed = persistence.update(scope(context), workspace(context), id, command.businessName(), command.commercialName(), command.contactPerson(), command.contactEmail(), command.phone(), command.deliveryProfile(), command.paymentCondition(), version);
		if (changed == 0) throw new CustomerRelationshipConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
		detail(context, id);
		if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw new ApiResourceNotFoundException("client-account-status");
		persistence.lockBuyerPaymentSetupAuthorization(scope(context), workspace(context), id);
		if (persistence.updateStatus(scope(context), workspace(context), id, status, version) == 0) throw new CustomerRelationshipConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
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
	@Override public ClientAccountBuyerMembershipPage buyerMemberships(
			CurrentAccessContext context, String id, int page, int size) {
		ClientAccountAccessPolicy.requireRead(context);
		accountForReader(context, id).orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
		var pageOfMemberships = persistence.listBuyerMemberships(scope(context), workspace(context), id,
				salesOwnerScope(context), page, size);
		if (pageOfMemberships.accountVersion() < 0) throw new ApiResourceNotFoundException("client-account");
		Map<String, BuyerMembershipCandidate> activeBuyers = buyerMemberships
				.findActiveBuyers(scope(context), workspace(context)).stream()
				.map(buyer -> new BuyerMembershipCandidate(buyer.id(), buyer.email(), buyer.displayName()))
				.collect(java.util.stream.Collectors.toMap(BuyerMembershipCandidate::id, buyer -> buyer));
		List<ClientAccountBuyerMembershipView> items = pageOfMemberships.items().stream().map(link -> {
			BuyerMembershipCandidate identity = activeBuyers.get(link.membershipId());
			return new ClientAccountBuyerMembershipView(link.membershipId(), link.status(),
					identity == null ? null : identity.email(), identity == null ? null : identity.displayName(),
					link.createdAt(), link.updatedAt());
		}).toList();
		return new ClientAccountBuyerMembershipPage(items, pageOfMemberships.page(), pageOfMemberships.size(),
				pageOfMemberships.total(), pageOfMemberships.accountVersion());
	}
	@Override @Transactional public ClientAccountBuyerMembershipMutation associateBuyerMembership(
			CurrentAccessContext context, String id, String membershipId, long version) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
		ClientAccountView account = detail(context, id);
		Optional<ClientAccountBuyerMembershipView> existing = persistence.findBuyerMembership(
				scope(context), workspace(context), id, membershipId, salesOwnerScope(context));
		if (existing.isPresent() && "ACTIVE".equals(existing.get().status())) {
			return new ClientAccountBuyerMembershipMutation(enrichMembership(context, existing.get()),
					account.version(), false, false);
		}
		if (account.version() != version) throw new CustomerRelationshipConflictException();
		BuyerMembershipCandidate identity = buyerMemberships.findActiveBuyer(scope(context), workspace(context), membershipId)
				.map(buyer -> new BuyerMembershipCandidate(buyer.id(), buyer.email(), buyer.displayName()))
				.orElseThrow(() -> new ApiResourceNotFoundException("buyer-membership"));
		long now = now();
		int changed;
		String eventType;
		boolean created;
		if (existing.isPresent()) {
			persistence.lockBuyerPaymentSetupAuthorization(scope(context), workspace(context), id);
			changed = persistence.changeBuyerMembershipStatus(scope(context), workspace(context), id, membershipId,
					salesOwnerScope(context), "INACTIVE", "ACTIVE", now, version);
			eventType = "CLIENT_ACCOUNT_BUYER_MEMBERSHIP_REACTIVATED";
			created = false;
		} else {
			if (persistence.isBuyerMembershipAssigned(scope(context), workspace(context), membershipId)) {
				throw new ApiResourceNotFoundException("buyer-membership");
			}
			if (account.accountType() == ClientAccountType.INDIVIDUAL
					&& persistence.listBuyerMemberships(scope(context), workspace(context), id, salesOwnerScope(context), 0, 1).total() > 0) {
				throw new CustomerRelationshipConflictException();
			}
			changed = persistence.associateBuyerMembership(scope(context), workspace(context), id, membershipId,
					salesOwnerScope(context), now, version);
			eventType = "CLIENT_ACCOUNT_BUYER_MEMBERSHIP_ASSOCIATED";
			created = changed == 1;
		}
		if (changed != 1) throw new CustomerRelationshipConflictException();
		long nextVersion = version + 1;
		recordBuyerMembershipChange(context, id, membershipId, eventType, "ACTIVE", nextVersion);
		ClientAccountBuyerMembershipView result = new ClientAccountBuyerMembershipView(membershipId, "ACTIVE",
				identity.email(), identity.displayName(), existing.map(ClientAccountBuyerMembershipView::createdAt)
						.orElse(Instant.ofEpochMilli(now)), Instant.ofEpochMilli(now));
		return new ClientAccountBuyerMembershipMutation(result, nextVersion, created, true);
	}
	@Override @Transactional public ClientAccountBuyerMembershipMutation deactivateBuyerMembership(
			CurrentAccessContext context, String id, String membershipId, long version) {
		ClientAccountAccessPolicy.requireManage(context);
		ClientAccountAccessPolicy.requireRead(context);
		ClientAccountView account = detail(context, id);
		ClientAccountBuyerMembershipView existing = persistence.findBuyerMembership(
				scope(context), workspace(context), id, membershipId, salesOwnerScope(context))
				.orElseThrow(() -> new ApiResourceNotFoundException("buyer-membership"));
		if ("INACTIVE".equals(existing.status())) {
			return new ClientAccountBuyerMembershipMutation(enrichMembership(context, existing), account.version(), false, false);
		}
		if (account.version() != version) throw new CustomerRelationshipConflictException();
		long now = now();
		persistence.lockBuyerPaymentSetupAuthorization(scope(context), workspace(context), id);
		if (persistence.changeBuyerMembershipStatus(scope(context), workspace(context), id, membershipId,
			salesOwnerScope(context), "ACTIVE", "INACTIVE", now, version) != 1) {
			throw new CustomerRelationshipConflictException();
		}
		long nextVersion = version + 1;
		recordBuyerMembershipChange(context, id, membershipId,
				"CLIENT_ACCOUNT_BUYER_MEMBERSHIP_DEACTIVATED", "INACTIVE", nextVersion);
		ClientAccountBuyerMembershipView result = new ClientAccountBuyerMembershipView(existing.membershipId(),
				"INACTIVE", existing.email(), existing.displayName(), existing.createdAt(), Instant.ofEpochMilli(now));
		return new ClientAccountBuyerMembershipMutation(result, nextVersion, false, true);
	}
	private ClientAccountBuyerMembershipView enrichMembership(CurrentAccessContext context,
			ClientAccountBuyerMembershipView membership) {
		return buyerMemberships.findActiveBuyer(scope(context), workspace(context), membership.membershipId())
				.map(identity -> new ClientAccountBuyerMembershipView(membership.membershipId(), membership.status(),
						identity.email(), identity.displayName(), membership.createdAt(), membership.updatedAt()))
				.orElse(membership);
	}
	private void recordBuyerMembershipChange(CurrentAccessContext context, String accountId, String membershipId,
			String eventType, String status, long accountVersion) {
		String actor = context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER) ? "BOM"
				: context.hasRole(MembershipRole.COMPANY_OWNER) ? "COMPANY_OWNER" : "SALES";
		traceability.record(new BusinessTraceabilityCommands.TraceRequest(
				context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(), actor,
				eventType, "ClientAccountBuyerMembership", UUID.fromString(membershipId), null,
				"client-account-buyer-membership:" + accountId + ":" + membershipId + ":v" + accountVersion,
				Map.of("clientAccountId", accountId, "buyerMembershipId", membershipId, "status", status), Instant.now()));
	}
	@Override @Transactional public ClientAccountSalesOwnerAssignment assignSalesOwner(CurrentAccessContext context,
			String id, String salesMembershipId, long version) {
		ClientAccountAccessPolicy.requireSalesOwnerAssignment(context);
		ActiveSalesMembershipDirectory.SalesMembershipReference seller = salesMemberships
				.findActiveSalesMembership(scope(context), workspace(context), salesMembershipId)
				.orElseThrow(() -> new ApiResourceNotFoundException("sales-membership"));
		ClientAccountView before = persistence.find(scope(context), workspace(context), id)
				.orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
		if (Objects.equals(before.salesOwnerMembershipId(), seller.id())) {
			return new ClientAccountSalesOwnerAssignment(before.id(), seller.id(), before.version());
		}
		if (before.version() != version) throw new CustomerRelationshipConflictException();
		if (persistence.updateSalesOwner(scope(context), workspace(context), id, UUID.fromString(seller.id()), version) == 0) {
			throw new CustomerRelationshipConflictException();
		}
		long nextVersion = version + 1;
		String eventType = before.salesOwnerMembershipId() == null
				? "CLIENT_ACCOUNT_SALES_OWNER_ASSIGNED" : "CLIENT_ACCOUNT_SALES_OWNER_TRANSFERRED";
		Map<String, Object> metadata = before.salesOwnerMembershipId() == null
				? Map.of("salesOwnerMembershipId", seller.id())
				: Map.of("previousSalesOwnerMembershipId", before.salesOwnerMembershipId(),
						"salesOwnerMembershipId", seller.id());
		traceability.record(new BusinessTraceabilityCommands.TraceRequest(
				context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
				context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER) ? "BOM" : "COMPANY_OWNER",
				eventType, "ClientAccount", UUID.fromString(before.id()), null,
				"client-account-sales-owner:" + before.id() + ":v" + nextVersion, metadata, Instant.now()));
		return new ClientAccountSalesOwnerAssignment(before.id(), seller.id(), nextVersion);
	}

	private static void validateDomain(ClientAccountView command) {
		if (command == null) throw new IllegalArgumentException("Client account details are required");
		new ClientCode(command.code());
		if (command.accountType() == ClientAccountType.INDIVIDUAL) {
			if (command.countryCode() != null || command.taxType() != null || command.taxValue() != null) {
				throw new IllegalArgumentException("Individual Buyer accounts cannot have business tax identifiers");
			}
		} else {
			new TaxIdentifier(command.countryCode(), command.taxType(), command.taxValue());
		}
		new BusinessName(command.businessName()); new CommercialName(command.commercialName()); new ContactEmail(command.contactEmail());
		new PhoneNumber(command.phone()); new PaymentCondition(command.paymentCondition());
	}
	private static ClientAccountView buyerAccount(BuyerClientAccountRegistrationCommand request,
			BuyerMembershipDirectory.BuyerMembershipReference identity, String membershipId) {
		if (request == null || request.accountType() == null) {
			throw new IllegalArgumentException("Buyer account type is required");
		}
		String displayName = new BusinessName(identity.displayName()).value();
		String email = new ContactEmail(identity.email()).value();
		String code = "BUY-" + membershipId.replace("-", "").substring(0, 24).toUpperCase(java.util.Locale.ROOT);
		String countryCode = null;
		String taxType = null;
		String taxValue = null;
		String businessName = displayName;
		String commercialName = displayName;
		String deliveryProfile = "";
		if (request.accountType() == ClientAccountType.BUSINESS) {
			businessName = new BusinessName(request.businessName()).value();
			commercialName = new CommercialName(request.commercialName()).value();
			countryCode = request.countryCode() == null || request.countryCode().isBlank() ? "PE" : request.countryCode();
			taxType = request.taxType() == null || request.taxType().isBlank() ? "RUC" : request.taxType();
			taxValue = request.taxValue();
			new TaxIdentifier(countryCode, taxType, taxValue);
			deliveryProfile = request.deliveryProfile() == null ? "" : request.deliveryProfile();
		} else if (hasBusinessDetails(request)) {
			throw new IllegalArgumentException("Individual Buyer registration cannot include business or tax details");
		}
		new PhoneNumber(request.phone());
		return new ClientAccountView(null, code, businessName, commercialName, countryCode, taxType, taxValue,
				"UNASSIGNED", displayName, email, request.phone().trim(), deliveryProfile, "CASH", "ACTIVE",
				membershipId, 0, request.accountType());
	}
	private static boolean hasBusinessDetails(BuyerClientAccountRegistrationCommand request) {
		return hasText(request.businessName()) || hasText(request.commercialName()) || hasText(request.countryCode())
				|| hasText(request.taxType()) || hasText(request.taxValue()) || hasText(request.deliveryProfile());
	}
	private static boolean hasText(String value) { return value != null && !value.isBlank(); }
	private Optional<ClientAccountView> accountForReader(CurrentAccessContext context, String id) {
		if (ClientAccountAccessPolicy.isSalesOwnerScoped(context)) {
			return persistence.findOwnedBySalesOwner(scope(context), workspace(context), id,
					context.membershipId().toString());
		}
		return persistence.find(scope(context), workspace(context), id);
	}
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static String salesOwnerScope(CurrentAccessContext context) {
		return ClientAccountAccessPolicy.isSalesOwnerScoped(context) ? context.membershipId().toString() : null;
	}
	private static long now() { return System.currentTimeMillis(); }
}
