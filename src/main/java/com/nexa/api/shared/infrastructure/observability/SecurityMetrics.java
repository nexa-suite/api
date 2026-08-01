package com.nexa.api.shared.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class SecurityMetrics {
	private final MeterRegistry registry;

	public SecurityMetrics(MeterRegistry registry) { this.registry = registry; }
	public void increment(String name) { registry.counter("nexa.security." + name).increment(); }
}
