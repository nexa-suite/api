package com.nexa.api.tenantaccessgovernance.support.application.port.in;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderProjection;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SupportConsoleUseCase {
	SupportRequestView request(UUID requesterOperatorId, UUID tenantId, UUID workspaceId,
			UUID salesOrderId, Instant expiresAt);
	List<SupportRequestView> listInternal(UUID operatorId, int limit);
	List<SupportRequestView> listOwner(CurrentAccessContext ownerContext, int limit);
	SupportRequestView consent(UUID requestId, CurrentAccessContext ownerContext);
	SupportRequestView approve(UUID requestId, UUID approverOperatorId);
	SupportRequestView revokeByOperator(UUID requestId, UUID operatorId);
	SupportRequestView revokeByOwner(UUID requestId, CurrentAccessContext ownerContext);
	SupportSalesOrderProjection readSalesOrder(UUID requestId, UUID requesterOperatorId);
}
