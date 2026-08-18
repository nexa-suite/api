package com.nexa.api.shared.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalMetricsTests {
    @Test
    void exposesTaggedTechnicalOperationAndStateSignals() {
        var registry = new SimpleMeterRegistry();
        var metrics = new TechnicalMetrics(registry);
        AtomicInteger pending = new AtomicInteger(3);

        metrics.count("scanner", "scan", "clean");
        metrics.start("stripe", "create_payment_intent").stop("timeout");
        metrics.gauge("outbox", "pending", pending::doubleValue);

        assertThat(registry.get("nexa.technical.operations").tag("component", "scanner").tag("operation", "scan").tag("outcome", "clean").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexa.technical.operation.duration").tag("component", "stripe").tag("operation", "create_payment_intent").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(registry.get("nexa.technical.state").tag("component", "outbox").tag("signal", "pending").gauge().value()).isEqualTo(3);

        pending.set(1);
        assertThat(registry.get("nexa.technical.state").tag("component", "outbox").tag("signal", "pending").gauge().value()).isEqualTo(1);
    }
}
