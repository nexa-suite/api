package com.nexa.api.shared.application.changefeed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleChangeFeedAudienceTests {
	@Test
	void roleDefinitionAndAssignmentChangesStayOwnerScoped() {
		assertThat(ChangeEventAudiences.forEvent("tenant.role-definition.updated", false))
				.containsExactly(ChangeEventAudience.OWNER);
		assertThat(ChangeEventAudiences.forEvent("organization.membership.role-definition-changed", false))
				.containsExactly(ChangeEventAudience.OWNER);
	}
}
