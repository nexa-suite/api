package com.nexa.api.tenantmanagement.application.port.in;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.InvitationModels;

import java.util.UUID;

public interface InvitationUseCase {
	InvitationModels.InvitationList list(CurrentAccessContext context, int page, int pageSize);
	InvitationModels.InvitationView detail(CurrentAccessContext context, UUID invitationId);
	InvitationModels.InvitationView create(CurrentAccessContext context, String email, String displayName,
			java.util.Set<String> roles, String idempotencyKey, String correlationId);
	InvitationModels.InvitationView revoke(CurrentAccessContext context, UUID invitationId, long expectedVersion, String correlationId);
	InvitationModels.InvitationView resend(CurrentAccessContext context, UUID invitationId, long expectedVersion, String correlationId);
	InvitationModels.InvitationAcceptanceResult accept(String token, String password, String displayName, String correlationId);
}
