package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

@org.springframework.modulith.NamedInterface(value = "support-order-read", propagate = false)
@FunctionalInterface
public interface SupportSalesOrderReadQuery {
	SupportSalesOrderProjection read();
}
