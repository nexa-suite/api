package com.nexa.api.customerbuyerrelationships.application.clientaccount.port;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;

@org.springframework.modulith.NamedInterface("customer-account-usecases")
public interface ClientAccountUseCase {
	CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size);
	ClientAccountView detail(CurrentAccessContext context, String id);
	ClientAccountView buyerDetail(CurrentAccessContext context);
	ClientAccountRegistrationResult registerOwnBusiness(CurrentAccessContext context, BuyerClientAccountRegistrationCommand command);
	List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context);
	ClientAccountView create(CurrentAccessContext context, ClientAccountView command);
	ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version);
	ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version);
	ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version);
	ClientAccountBuyerMembershipPage buyerMemberships(CurrentAccessContext context, String id, int page, int size);
	ClientAccountBuyerMembershipMutation associateBuyerMembership(CurrentAccessContext context, String id,
			String membershipId, long version);
	ClientAccountBuyerMembershipMutation deactivateBuyerMembership(CurrentAccessContext context, String id,
			String membershipId, long version);
	ClientAccountSalesOwnerAssignment assignSalesOwner(CurrentAccessContext context, String id, String salesMembershipId, long version);
}
