package com.nexa.api.shared.application.port.out;

/** Framework-neutral boundary for recording security outcomes. */
public interface SecurityMetricsPort {
    void increment(String name);
}
