package com.nexa.api.tenantaccessgovernance.support.application.port.out;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportRequestPersistencePort {
	SupportRequestView create(UUID id, UUID tenantId, UUID workspaceId, UUID salesOrderId,
			UUID requesterOperatorId, Instant expiresAt, Instant now);
	List<SupportRequestView> listInternal(UUID operatorId, Instant now, int limit);
	List<SupportRequestView> listOwner(UUID tenantId, UUID workspaceId, Instant now, int limit);
	Optional<SupportRequestView> findForOperator(UUID requestId, UUID operatorId, Instant now);
	Optional<SupportRequestView> consent(UUID requestId, UUID tenantId, UUID workspaceId, UUID ownerUserId,
			UUID ownerMembershipId, Instant now);
	Optional<SupportRequestView> approve(UUID requestId, UUID approverOperatorId, Instant now);
	Optional<SupportRequestView> revokeByOperator(UUID requestId, UUID operatorId, Instant now);
	Optional<SupportRequestView> revokeByOwner(UUID requestId, UUID tenantId, UUID workspaceId,
			UUID ownerUserId, UUID ownerMembershipId, Instant now);
	void appendOrderRead(SupportRequestView request, UUID requesterOperatorId, Instant now);
	boolean isActive(SupportOrderReadGrant grant, Instant now);
}
