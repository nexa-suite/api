package com.nexa.api.customerbuyerrelationships.application.clientaccount.port;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipView;

import java.util.Optional;
import java.util.UUID;

public interface ClientAccountPersistencePort {
	CustomerAccountPage<ClientAccountView> list(String tenantId, String workspaceId, String search, String status, int page, int size);
	CustomerAccountPage<ClientAccountView> listForSalesOwner(String tenantId, String workspaceId, String salesOwnerMembershipId,
			String search, String status, int page, int size);
	Optional<ClientAccountView> find(String tenantId, String workspaceId, String id);
	Optional<ClientAccountView> findOwnedBySalesOwner(String tenantId, String workspaceId, String id,
			String salesOwnerMembershipId);
	void insert(ClientAccountView command, String tenantId, String workspaceId, UUID id, UUID salesOwnerMembershipId,
			long nowEpochMillis);
	int update(String tenantId, String workspaceId, String id, String businessName, String commercialName, String contactPerson,
			String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version);
	void lockBuyerPaymentSetupAuthorization(String tenantId, String workspaceId, String clientAccountId);
	int updateStatus(String tenantId, String workspaceId, String id, String status, long version);
	int updateSalesOwner(String tenantId, String workspaceId, String id, UUID salesOwnerMembershipId, long version);
	Optional<ClientAccountView> findForBuyer(String tenantId, String workspaceId, String membershipId);
	Optional<ClientAccountView> findAnyForBuyer(String tenantId, String workspaceId, String membershipId);
	boolean isExactBuyerRegistration(String tenantId, String workspaceId, String membershipId,
			ClientAccountView requested);
	boolean isBuyerMembershipAssigned(String tenantId, String workspaceId, String membershipId);
	int associateBuyer(String tenantId, String workspaceId, String accountId, String membershipId, UUID associationId, long nowEpochMillis, long version);
	ClientAccountBuyerMembershipPage listBuyerMemberships(
			String tenantId, String workspaceId, String accountId, String salesOwnerMembershipId, int page, int size);
	Optional<ClientAccountBuyerMembershipView> findBuyerMembership(String tenantId, String workspaceId,
			String accountId, String membershipId, String salesOwnerMembershipId);
	int associateBuyerMembership(String tenantId, String workspaceId, String accountId, String membershipId,
			String salesOwnerMembershipId, long nowEpochMillis, long accountVersion);
	int changeBuyerMembershipStatus(String tenantId, String workspaceId, String accountId, String membershipId,
			String salesOwnerMembershipId, String expectedStatus, String newStatus, long nowEpochMillis,
			long accountVersion);
}
