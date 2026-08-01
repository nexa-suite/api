package com.nexa.api.shared.application.changefeed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeEventAudiencePolicyTests {
	@Test void mapsInternalEventsOnlyToOwner() {
		assertThat(ChangeEventAudiences.forEvent("organization.workspace.updated", false))
				.containsExactly(ChangeEventAudience.OWNER);
	}
	@Test void buyerVisibilityIsExplicitAndScoped() {
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.created", false))
				.containsExactly(ChangeEventAudience.SALES);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.created", true))
				.containsExactlyInAnyOrder(ChangeEventAudience.SALES, ChangeEventAudience.BUYER);
	}
	@Test void unknownEventsFailClosed() {
		assertThatThrownBy(() -> ChangeEventAudiences.forEvent("future.unknown.event", false))
				.isInstanceOf(IllegalStateException.class);
	}
}
