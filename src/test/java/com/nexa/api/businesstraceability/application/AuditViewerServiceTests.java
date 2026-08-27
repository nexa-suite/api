package com.nexa.api.businesstraceability.application;

import com.nexa.api.businesstraceability.application.model.AuditModels.AuditEventRecord;
import com.nexa.api.businesstraceability.application.port.out.AuditViewerQueryPort;
import com.nexa.api.businesstraceability.application.service.AuditViewerService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.businesstraceability.application.service.SafeAuditMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditViewerServiceTests {
	@Test
	void tenantAdminReceivesOnlyAllowlistedMetadata() {
		AuditViewerQueryPort query = mock(AuditViewerQueryPort.class);
		when(query.list("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10", "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17", 10))
				.thenReturn(List.of(new AuditEventRecord("a7a5a7d1-49a0-4d5c-a9d7-0b65784c6991",
					"3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10", "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17",
					"24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2", "PLATFORM", "ROLE_CHANGED", "membership",
					"c7e9ab18-114e-4b91-9bf7-72172aa9a0a4", "corr-1", Instant.parse("2026-08-02T10:15:00Z"),
					Map.of("status", "ACTIVE", "sessionId", "secret", "token", "secret", "beforeRoles", List.of("SALES")))));
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.hasRole(MembershipRole.TENANT_ADMIN)).thenReturn(true);
		when(context.tenantId()).thenReturn(new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10"));
		when(context.workspaceId()).thenReturn(new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17"));

		var event = new AuditViewerService(query).list(context, 10).items().getFirst();

		assertThat(event.metadata()).containsEntry("status", "ACTIVE").containsEntry("beforeRoles", List.of("SALES"));
		assertThat(event.metadata()).doesNotContainKeys("sessionId", "token");
	}

	@Test
	void tenantAdminReceivesAllowlistedOrganizationChangeValues() {
		Map<String, Object> oldValues = Map.of("legalName", "Old Legal", "displayName", "Old Display",
				"businessIdentifier", "OLD-1", "operationCategory", "B2B_COLD_CHAIN_DISTRIBUTOR", "token", "secret");
		Map<String, Object> newValues = Map.of("legalName", "New Legal", "displayName", "New Display",
				"businessIdentifier", "NEW-1", "operationCategory", "B2B_COLD_CHAIN_DISTRIBUTOR");
		Map<String, Object> raw = Map.of("section", "organization", "oldValues", oldValues, "newValues", newValues, "token", "secret");

		assertThat(SafeAuditMetadata.sanitize(raw)).containsEntry("oldValues", Map.of("legalName", "Old Legal", "displayName", "Old Display",
				"businessIdentifier", "OLD-1", "operationCategory", "B2B_COLD_CHAIN_DISTRIBUTOR"))
				.containsEntry("newValues", newValues)
				.doesNotContainKey("token");
	}

	@Test
	void nonTenantAdminCannotUseViewer() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.hasRole(MembershipRole.TENANT_ADMIN)).thenReturn(false);

		assertThatThrownBy(() -> new AuditViewerService(mock(AuditViewerQueryPort.class)).list(context, 10))
				.isInstanceOf(AccessPolicyViolation.class);
	}
}
