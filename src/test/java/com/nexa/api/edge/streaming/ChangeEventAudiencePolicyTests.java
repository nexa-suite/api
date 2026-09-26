package com.nexa.api.edge.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeEventAudiencePolicyTests {
	@Test void mapsInternalEventsOnlyToOwner() {
		assertThat(ChangeEventAudiences.forEvent("organization.workspace.updated", false))
				.containsExactly(ChangeEventAudience.OWNER);
		assertThat(ChangeEventAudiences.forEvent("organization.membership.revoked", false))
				.containsExactly(ChangeEventAudience.OWNER);
	}
	@Test void buyerVisibilityIsExplicitAndScoped() {
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.created", false))
				.containsExactly(ChangeEventAudience.SALES);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.created", true))
				.containsExactlyInAnyOrder(ChangeEventAudience.SALES, ChangeEventAudience.BUYER);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.material-change-proposed", false))
				.containsExactly(ChangeEventAudience.SALES);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.material-change-proposed", true))
				.containsExactlyInAnyOrder(ChangeEventAudience.SALES, ChangeEventAudience.BUYER);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.material-change-rejected", false))
				.containsExactly(ChangeEventAudience.SALES);
		assertThat(ChangeEventAudiences.forEvent("sales.purchase-request.withdrawn", false))
				.containsExactly(ChangeEventAudience.SALES);
	}
	@Test void operationalHandoffIsVisibleOnlyToWarehouseAndLogistics() {
		assertThat(ChangeEventAudiences.forEvent("warehouse.logistics.handoff-note", false))
				.containsExactlyInAnyOrder(ChangeEventAudience.WAREHOUSE, ChangeEventAudience.LOGISTICS);
	}
	@Test void unknownEventsFailClosed() {
		assertThatThrownBy(() -> ChangeEventAudiences.forEvent("future.unknown.event", false))
				.isInstanceOf(IllegalStateException.class);
	}
}
