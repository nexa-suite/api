package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAccessPolicyAdapterOrderingTests {
	@Test
	void ordersContextsByTenantNameThenWorkspaceNameThenMembershipId() {
		AccessPolicy betaTenant = policy("Beta", "A Workspace", "membership-a");
		AccessPolicy laterWorkspace = policy("Alpha", "Z Workspace", "membership-a");
		AccessPolicy laterMembership = policy("Alpha", "A Workspace", "membership-z");
		AccessPolicy first = policy("Alpha", "A Workspace", "membership-a");

		assertThat(JdbcAccessPolicyAdapter.orderAccessContexts(
				List.of(betaTenant, laterWorkspace, laterMembership, first)))
				.containsExactly(first, laterMembership, laterWorkspace, betaTenant);
	}

	private static AccessPolicy policy(String tenantName, String workspaceName, String membershipId) {
		return new AccessPolicy(ClientSurface.PLATFORM, Set.of("operator"), Set.of(),
				tenantName + "-tenant-id", tenantName.toLowerCase(), workspaceName + "-workspace-id",
				workspaceName.toLowerCase().replace(' ', '-'), membershipId, "User", "en", 1, Set.of(),
				tenantName, workspaceName);
	}
}
