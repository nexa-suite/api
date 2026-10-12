package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

/** Rechecks the central grant immediately before and after a tenant-scoped read. */
@org.springframework.modulith.NamedInterface(value = "support-order-read", propagate = false)
@FunctionalInterface
public interface SupportOrderReadGrantValidationPort {
	boolean isActive(SupportOrderReadGrant grant);
}
