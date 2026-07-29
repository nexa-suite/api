package com.nexa.api.sales.application.port.out;

import com.nexa.api.sales.application.model.*;
import com.nexa.api.sales.domain.CatalogItemSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface SalesPort {
	SalesPage<ClientAccountView> listClientAccounts(String tenantId, String workspaceId, String search, String status, int page, int size);
	Optional<ClientAccountView> findClientAccount(String tenantId, String workspaceId, String id);
	void insertClientAccount(ClientAccountView command, String tenantId, String workspaceId, UUID id, long nowEpochMillis);
	int updateClientAccount(String tenantId, String workspaceId, String id, String businessName, String commercialName, String contactPerson,
			String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version);
	int updateClientAccountStatus(String tenantId, String workspaceId, String id, String status, long version);
	Optional<ClientAccountView> findClientAccountForBuyer(String tenantId, String workspaceId, String membershipId);
	boolean isAvailableBuyerMembership(String tenantId, String workspaceId, String membershipId);
	int associateBuyer(String tenantId, String workspaceId, String accountId, String membershipId, UUID associationId, long nowEpochMillis, long version);

	SalesPage<PurchaseRequestView> listPurchaseRequests(String tenantId, String workspaceId, String buyerAccountId, PurchaseRequestFilter filter);
	Optional<PurchaseRequestView> findPurchaseRequest(String tenantId, String workspaceId, String buyerAccountId, String id);
	void insertPurchaseRequest(PurchaseRequestView request, String tenantId, String workspaceId, UUID id, long nowEpochMillis);
	void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long nowEpochMillis);
	int updatePurchaseRequest(String tenantId, String workspaceId, String buyerAccountId, String id, String priority,
			LocalDate requestedDeliveryDate, String deliveryProfileSnapshot, String paymentOption, String comment, long version);
	int updateLine(String requestId, String lineId, BigDecimal quantity, String notes, long version);
	int deleteLine(String requestId, String lineId, long version);
	int transition(String tenantId, String workspaceId, String buyerAccountId, String id, String fromStatus, String toStatus,
			String reviewNote, String actorMembershipId, long version, UUID eventId, long nowEpochMillis);
	Optional<IdempotencyResult> findIdempotency(String tenantId, String workspaceId, String actorMembershipId, String operation, String key);
	void saveIdempotency(String tenantId, String workspaceId, String actorMembershipId, String operation, String key, String resourceId, long responseVersion, UUID id, long nowEpochMillis);
	Optional<CatalogItemSnapshot> findActiveCatalogItem(String catalogItemId);

	record IdempotencyResult(String resourceId, long responseVersion) { }
}
