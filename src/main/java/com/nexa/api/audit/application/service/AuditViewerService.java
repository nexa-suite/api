package com.nexa.api.audit.application.service;

import com.nexa.api.audit.application.model.AuditModels.AuditEventRecord;
import com.nexa.api.audit.application.model.AuditModels.AuditEventView;
import com.nexa.api.audit.application.model.AuditModels.AuditPage;
import com.nexa.api.audit.application.port.in.AuditViewerUseCase;
import com.nexa.api.audit.application.port.out.AuditViewerQueryPort;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Objects;

public final class AuditViewerService implements AuditViewerUseCase {
	private final AuditViewerQueryPort query;

	public AuditViewerService(AuditViewerQueryPort query) { this.query = Objects.requireNonNull(query, "Audit query port is required"); }

	@Override
	public AuditPage list(CurrentAccessContext context, int limit) {
		authorize(context);
		int safeLimit = Math.min(100, Math.max(1, limit));
		return new AuditPage(query.list(context.tenantId().toString(), context.workspaceId().toString(), safeLimit)
				.stream().map(AuditViewerService::view).toList(), safeLimit);
	}

	@Override
	public AuditEventView detail(CurrentAccessContext context, String id) {
		authorize(context);
		AuditEventRecord event = query.find(context.tenantId().toString(), context.workspaceId().toString(), id)
				.orElseThrow(() -> new ApiResourceNotFoundException("audit event"));
		return view(event);
	}

	private static void authorize(CurrentAccessContext context) {
		if (!context.hasRole(MembershipRole.TENANT_ADMIN)) throw new AccessPolicyViolation("Tenant administrator audit access is required");
		context.requirePermission(PermissionKey.TENANT_AUDIT_READ);
	}

	private static AuditEventView view(AuditEventRecord event) {
		return new AuditEventView(event.id(), event.tenantId(), event.workspaceId(), event.actorMembershipId(), event.actorWorkArea(),
				event.eventType(), event.subjectType(), event.subjectId(), event.correlationId(), event.occurredAt(), SafeAuditMetadata.sanitize(event.metadata()));
	}
}
