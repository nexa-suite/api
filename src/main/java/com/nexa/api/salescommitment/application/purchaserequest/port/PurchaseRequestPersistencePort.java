package com.nexa.api.salescommitment.application.purchaserequest.port;

import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.purchaserequest.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PurchaseRequestPersistencePort {
	SalesPage<PurchaseRequestView> list(String tenantId, String workspaceId, String buyerAccountId, PurchaseRequestFilter filter);
	Optional<PurchaseRequestView> find(String tenantId, String workspaceId, String buyerAccountId, String id);
	List<PurchaseRequestEventView> events(String tenantId, String workspaceId, String buyerAccountId, String id);
	void insert(PurchaseRequestView request, String tenantId, String workspaceId, UUID id, long nowEpochMillis);
	default void insert(PurchaseRequestView request, String tenantId, String workspaceId, UUID id,
			long nowEpochMillis, String walletBeneficiaryIdentityId) {
		insert(request, tenantId, workspaceId, id, nowEpochMillis);
	}
	void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long nowEpochMillis);
	int update(String tenantId, String workspaceId, String buyerAccountId, String id, String priority, LocalDate requestedDeliveryDate,
			String deliveryProfileSnapshot, String paymentOption, String comment, long version);
	default int update(String tenantId, String workspaceId, String buyerAccountId, String id, String priority,
			LocalDate requestedDeliveryDate, String deliveryProfileSnapshot, String paymentOption,
			String comment, long version, String walletBeneficiaryIdentityId) {
		return update(tenantId, workspaceId, buyerAccountId, id, priority, requestedDeliveryDate,
				deliveryProfileSnapshot, paymentOption, comment, version);
	}
	int updateLine(String tenantId, String workspaceId, String buyerAccountId, String requestId, String lineId,
			BigDecimal quantity, String notes, long version);
	int deleteLine(String tenantId, String workspaceId, String buyerAccountId, String requestId, String lineId, long version);
	int transition(String tenantId, String workspaceId, String buyerAccountId, String id, String fromStatus, String toStatus,
			String reviewNote, String actorMembershipId, long version);
	default int transition(String tenantId, String workspaceId, String buyerAccountId, String id,
			String fromStatus, String toStatus, String reviewNote, String actorMembershipId, long version,
			String walletBeneficiaryIdentityId) {
		return transition(tenantId, workspaceId, buyerAccountId, id, fromStatus, toStatus,
			reviewNote, actorMembershipId, version);
	}
}
