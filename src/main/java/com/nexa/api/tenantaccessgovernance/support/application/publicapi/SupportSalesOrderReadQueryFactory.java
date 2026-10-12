package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

/** BC01 capability handed to the narrow BC04 read adapter. */
@org.springframework.modulith.NamedInterface(value = "support-order-read", propagate = false)
@FunctionalInterface
public interface SupportSalesOrderReadQueryFactory {
	SupportSalesOrderReadQuery open(SupportOrderReadGrant grant);
}
