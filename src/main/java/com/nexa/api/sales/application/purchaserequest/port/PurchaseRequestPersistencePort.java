package com.nexa.api.sales.application.purchaserequest.port;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.purchaserequest.model.*;

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
	void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long nowEpochMillis);
	int update(String tenantId, String workspaceId, String buyerAccountId, String id, String priority, LocalDate requestedDeliveryDate,
			String deliveryProfileSnapshot, String paymentOption, String comment, long version);
	int updateLine(String tenantId, String workspaceId, String buyerAccountId, String requestId, String lineId,
			BigDecimal quantity, String notes, long version);
	int deleteLine(String tenantId, String workspaceId, String buyerAccountId, String requestId, String lineId, long version);
	int transition(String tenantId, String workspaceId, String buyerAccountId, String id, String fromStatus, String toStatus,
			String reviewNote, String actorMembershipId, long version);
}
