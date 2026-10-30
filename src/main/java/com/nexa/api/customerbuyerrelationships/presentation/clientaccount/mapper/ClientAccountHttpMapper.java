package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.mapper;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipView;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.CreateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.RegisterBuyerClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request.UpdateClientAccountRequest;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountDetailResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.BuyerClientAccountRegistrationResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.BuyerClientAccountSelfResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountPageResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountSummaryResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountSalesOwnerAssignmentResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountBuyerMembershipResponse;
import com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response.ClientAccountBuyerMembershipPageResponse;
import org.springframework.stereotype.Component;

@Component
public class ClientAccountHttpMapper {
	public ClientAccountView create(CreateClientAccountRequest request) { return new ClientAccountView(null, request.code(), request.businessName(), request.commercialName(), request.countryCode() == null ? "PE" : request.countryCode(), request.taxType() == null ? "RUC" : request.taxType(), request.taxValue(), request.segment(), request.contactPerson(), request.contactEmail(), request.phone(), request.deliveryProfile(), request.paymentCondition(), "ACTIVE", null, 0, ClientAccountType.BUSINESS); }
	public BuyerClientAccountRegistrationCommand buyerRegistration(RegisterBuyerClientAccountRequest request) {
		return new BuyerClientAccountRegistrationCommand(request.accountType(),
				request.businessName(), request.commercialName(), request.countryCode(), request.taxType(), request.taxValue(),
				request.phone(), request.deliveryProfile());
	}
	public ClientAccountView update(UpdateClientAccountRequest request) { return new ClientAccountView(null, null, request.businessName(), request.commercialName(), null, null, null, null, request.contactPerson(), request.contactEmail(), request.phone(), request.deliveryProfile(), request.paymentCondition(), null, null, 0, null); }
	public ClientAccountDetailResponse detail(ClientAccountView view) { return new ClientAccountDetailResponse(view.id(), view.code(), view.businessName(), view.commercialName(), view.countryCode(), view.taxType(), view.taxValue(), view.segment(), view.contactPerson(), view.contactEmail(), view.phone(), view.deliveryProfile(), view.paymentCondition(), view.status(), view.buyerMembershipId(), view.version(), view.accountType()); }
	public BuyerClientAccountRegistrationResponse buyerRegistration(ClientAccountView view) {
		return new BuyerClientAccountRegistrationResponse(view.id(), view.code(), view.businessName(),
				view.commercialName(), view.countryCode(), view.taxType(), view.taxValue(), view.segment(),
				view.contactPerson(), view.contactEmail(), view.phone(), view.deliveryProfile(),
				view.paymentCondition(), view.status(), view.buyerMembershipId(), view.version(), view.accountType());
	}
	public BuyerClientAccountSelfResponse buyerSelf(ClientAccountView view, String membershipId) {
		return new BuyerClientAccountSelfResponse(view.id(), view.code(), view.businessName(), view.commercialName(),
				view.countryCode(), view.taxType(), view.taxValue(), view.segment(), null, null, null,
				view.deliveryProfile(), view.paymentCondition(), view.status(), membershipId, view.version(),
				view.accountType());
	}
	public ClientAccountSummaryResponse summary(ClientAccountView view) { return new ClientAccountSummaryResponse(view.id(), view.code(), view.businessName(), view.commercialName(), view.segment(), view.status(), view.buyerMembershipId(), view.version(), view.accountType(), view.salesOwnerMembershipId()); }
	public ClientAccountSalesOwnerAssignmentResponse salesOwnerAssignment(ClientAccountSalesOwnerAssignment assignment) {
		return new ClientAccountSalesOwnerAssignmentResponse(assignment.clientAccountId(),
				assignment.salesOwnerMembershipId(), assignment.accountVersion());
	}
	public ClientAccountBuyerMembershipResponse buyerMembership(ClientAccountBuyerMembershipView membership) {
		return new ClientAccountBuyerMembershipResponse(membership.membershipId(), membership.status(),
				membership.email(), membership.displayName(), membership.createdAt(), membership.updatedAt());
	}
	public ClientAccountBuyerMembershipPageResponse buyerMembershipPage(ClientAccountBuyerMembershipPage page) {
		return new ClientAccountBuyerMembershipPageResponse(page.items().stream().map(this::buyerMembership).toList(),
				page.page(), page.size(), page.total());
	}
	public ClientAccountPageResponse page(CustomerAccountPage<ClientAccountView> page) { return new ClientAccountPageResponse(page.items().stream().map(this::summary).toList(), page.page(), page.size(), page.total()); }
}
