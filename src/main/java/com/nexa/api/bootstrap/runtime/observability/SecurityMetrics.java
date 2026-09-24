package com.nexa.api.bootstrap.runtime.observability;

import io.micrometer.core.instrument.MeterRegistry;
import com.nexa.api.shared.application.port.out.SecurityMetricsPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class SecurityMetrics implements SecurityMetricsPort {
	private final MeterRegistry registry;

	public SecurityMetrics(MeterRegistry registry) { this.registry = registry; }
	public void increment(String name) { registry.counter("nexa.security." + name).increment(); }
}
