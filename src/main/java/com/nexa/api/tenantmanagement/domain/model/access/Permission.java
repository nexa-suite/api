package com.nexa.api.tenantmanagement.domain.model.access;

public enum Permission {
	TENANT_READ("tenant:read"),
	TENANT_WRITE("tenant:write"),
	WORKSPACE_READ("workspace:read"),
	WORKSPACE_WRITE("workspace:write"),
	MEMBERSHIP_READ("membership:read"),
	MEMBERSHIP_WRITE("membership:write"),
	CATALOG_READ("catalog:read"),
	CATALOG_WRITE("catalog:write"),
	SALES_READ("sales:read"),
	SALES_WRITE("sales:write"),
	ORDERS_READ("orders:read"),
	ORDERS_WRITE("orders:write"),
	DOCUMENTS_READ("documents:read"),
	DOCUMENTS_WRITE("documents:write"),
	WAREHOUSE_READ("warehouse:read"),
	WAREHOUSE_WRITE("warehouse:write"),
	INVENTORY_READ("inventory:read"),
	INVENTORY_WRITE("inventory:write"),
	LOGISTICS_READ("logistics:read"),
	LOGISTICS_WRITE("logistics:write"),
	SHIPMENTS_READ("shipments:read"),
	SHIPMENTS_WRITE("shipments:write"),
	DISPATCH_READ("dispatch:read"),
	DISPATCH_WRITE("dispatch:write"),
	PORTAL_READ("portal:read"),
	PORTAL_WRITE("portal:write"),
	REQUESTS_READ("requests:read"),
	REQUESTS_WRITE("requests:write"),
	ANALYTICS_READ("analytics:read");

	private final String code;

	Permission(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
