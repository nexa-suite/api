package com.nexa.api.shared.application.port.out;

import java.util.function.DoubleSupplier;

/** Framework-neutral boundary for recording technical runtime measurements. */
public interface TechnicalMetricsPort {
    TimerSample start(String component, String operation);

    void count(String component, String operation, String outcome);

    void gauge(String component, String signal, DoubleSupplier value);

    interface TimerSample {
        void stop(String outcome);
    }
}
