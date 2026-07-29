package com.nexa.api.tenantmanagement.domain.model.access;

public enum Permission {
	CATALOG_READ("catalog:read"),
	IAM_USERS_READ("iam:users:read"),
	IAM_USERS_MANAGE("iam:users:manage"),
	TENANT_READ("tenant:read"),
	TENANT_MANAGE("tenant:manage"),
	SALES_READ("sales:read"),
	SALES_WRITE("sales:write"),
	SALES_BUYER_READ("sales:buyer:read"),
	SALES_BUYER_WRITE("sales:buyer:write"),
	WAREHOUSE_READ("warehouse:read"),
	WAREHOUSE_WRITE("warehouse:write"),
	LOGISTICS_READ("logistics:read"),
	LOGISTICS_WRITE("logistics:write"),
	LOGISTICS_BUYER_READ("logistics:buyer:read"),
	INVOICING_READ("invoicing:read"),
	INVOICING_WRITE("invoicing:write"),
	INVOICING_BUYER_READ("invoicing:buyer:read");

	private final String code;

	Permission(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
