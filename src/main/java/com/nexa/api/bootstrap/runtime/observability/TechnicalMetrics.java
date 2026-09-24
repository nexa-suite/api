package com.nexa.api.bootstrap.runtime.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import com.nexa.api.shared.application.port.out.TechnicalMetricsPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

@Component
@Profile("!test")
public final class TechnicalMetrics implements TechnicalMetricsPort {
    private final MeterRegistry registry;

    public TechnicalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public TechnicalMetricsPort.TimerSample start(String component, String operation) {
        return new TimerSample(component, operation, registry);
    }

    public void count(String component, String operation, String outcome) {
        registry.counter("nexa.technical.operations", "component", component, "operation", operation, "outcome", outcome).increment();
    }

    public void gauge(String component, String signal, DoubleSupplier value) {
        Gauge.builder("nexa.technical.state", value, supplier -> supplier.getAsDouble())
                .tag("component", component)
                .tag("signal", signal)
                .register(registry);
    }

    public static final class TimerSample implements TechnicalMetricsPort.TimerSample {
        private final String component;
        private final String operation;
        private final MeterRegistry registry;
        private final long startedAt = System.nanoTime();

        private TimerSample(String component, String operation, MeterRegistry registry) {
            this.component = component;
            this.operation = operation;
            this.registry = registry;
        }

        public void stop(String outcome) {
            registry.timer("nexa.technical.operation.duration", "component", component, "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
