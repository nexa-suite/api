package com.nexa.api.sales.application.clientaccount.port;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.model.SalesPage;

import java.util.Optional;
import java.util.UUID;

public interface ClientAccountPersistencePort {
	SalesPage<ClientAccountView> list(String tenantId, String workspaceId, String search, String status, int page, int size);
	Optional<ClientAccountView> find(String tenantId, String workspaceId, String id);
	void insert(ClientAccountView command, String tenantId, String workspaceId, UUID id, long nowEpochMillis);
	int update(String tenantId, String workspaceId, String id, String businessName, String commercialName, String contactPerson,
			String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version);
	int updateStatus(String tenantId, String workspaceId, String id, String status, long version);
	Optional<ClientAccountView> findForBuyer(String tenantId, String workspaceId, String membershipId);
	boolean isAvailableBuyerMembership(String tenantId, String workspaceId, String membershipId);
	int associateBuyer(String tenantId, String workspaceId, String accountId, String membershipId, UUID associationId, long nowEpochMillis, long version);
}
