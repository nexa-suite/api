package com.nexa.api.tenantmanagement.domain.model.access;

public enum Permission {
	CATALOG_READ("catalog:read"),
	CATALOG_MANAGE("catalog:manage"),
	CATALOG_PRICE_MANAGE("catalog:price:manage"),
	PROMOTION_READ("promotion:read"),
	PROMOTION_MANAGE("promotion:manage"),
	IAM_USER_READ("iam:user:read"),
	IAM_USER_MANAGE("iam:user:manage"),
	TENANT_READ("tenant:read"),
	TENANT_MANAGE("tenant:manage"),
	OWNER_DASHBOARD_READ("owner:dashboard:read"),
	SALES_READ("sales:read"),
	SALES_WRITE("sales:write"),
	SALES_BUYER_READ("sales:buyer:read"),
	SALES_BUYER_WRITE("sales:buyer:write"),
	ORDERS_BUYER_READ("orders:buyer:read"),
	WAREHOUSE_READ("warehouse:read"),
	WAREHOUSE_WRITE("warehouse:write"),
	FULFILLMENT_READ("fulfillment:read"),
	LOGISTICS_READ("logistics:read"),
	LOGISTICS_WRITE("logistics:write"),
	TRACKING_BUYER_READ("tracking:buyer:read"),
	DOCUMENTS_SALES_READ("documents:sales:read"),
	DOCUMENTS_SALES_WRITE("documents:sales:write"),
	DOCUMENTS_OPERATIONS_READ("documents:operations:read"),
	DOCUMENTS_OPERATIONS_WRITE("documents:operations:write"),
	DOCUMENTS_BUYER_READ("documents:buyer:read"),
	PROFILE_BUYER_WRITE("profile:buyer:write");

	private final String code;

	Permission(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
