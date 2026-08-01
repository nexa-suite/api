package com.nexa.api.shared.application.changefeed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeFeedConnectionRegistryTests {
	@Test void rejectsSessionOverflowAndReleasesExactlyOnce() {
		var registry = new ChangeFeedConnectionRegistry(2, 1, 3, 50);
		var lease = registry.reserve("session", "user:PLATFORM", "tenant:workspace");
		assertThatThrownBy(() -> registry.reserve("session", "user:PLATFORM", "tenant:workspace"))
				.isInstanceOf(ChangeFeedCapacityException.class);
		lease.close(); lease.close();
		registry.reserve("session", "user:PLATFORM", "tenant:workspace").close();
	}
}
