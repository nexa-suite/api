package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

import java.math.BigDecimal;

/** Data minimization boundary: no party, address, note, or sales-order line data. */
@org.springframework.modulith.NamedInterface(value = "support-order-read", propagate = false)
public record SupportSalesOrderProjection(String status, BigDecimal total, String currency, long version) {
	public SupportSalesOrderProjection {
		if (status == null || status.isBlank()) throw new IllegalArgumentException("Order status is required");
		if (total == null || total.signum() < 0) throw new IllegalArgumentException("Order total is invalid");
		if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Order currency is invalid");
		if (version < 0) throw new IllegalArgumentException("Order version is invalid");
	}
}
