package com.nexa.api.tenantmanagement.domain.model.access;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Closed permission vocabulary. HTTP callers can select only values from this
 * catalog; arbitrary authority strings never become tenant permissions.
 */
public enum PermissionKey {
	TENANT_ORGANIZATION_READ("tenant.organization.read", PermissionGroup.TENANT_ADMINISTRATION, "tenant:read"),
	TENANT_ORGANIZATION_MANAGE("tenant.organization.manage", PermissionGroup.TENANT_ADMINISTRATION, "tenant:manage"),
	TENANT_WORKSPACE_READ("tenant.workspace.read", PermissionGroup.TENANT_ADMINISTRATION, "tenant:read"),
	TENANT_WORKSPACE_MANAGE("tenant.workspace.manage", PermissionGroup.TENANT_ADMINISTRATION, "tenant:manage"),
	TENANT_MEMBER_READ("tenant.member.read", PermissionGroup.MEMBERS_AND_ROLES, "iam:user:read"),
	TENANT_MEMBER_INVITE("tenant.member.invite", PermissionGroup.MEMBERS_AND_ROLES, "iam:user:manage"),
	TENANT_MEMBER_MANAGE("tenant.member.manage", PermissionGroup.MEMBERS_AND_ROLES, "iam:user:manage"),
	TENANT_ROLE_READ("tenant.role.read", PermissionGroup.MEMBERS_AND_ROLES, "tenant:read"),
	TENANT_ROLE_MANAGE("tenant.role.manage", PermissionGroup.MEMBERS_AND_ROLES, "tenant:manage"),
	TENANT_ROLE_ASSIGN("tenant.role.assign", PermissionGroup.MEMBERS_AND_ROLES, "tenant:manage"),
	TENANT_ROLE_ASSIGN_RESERVED("tenant.role.assign_reserved", PermissionGroup.MEMBERS_AND_ROLES, "tenant:manage"),
	TENANT_SECURITY_MANAGE("tenant.security.manage", PermissionGroup.TENANT_ADMINISTRATION, "tenant:manage"),
	TENANT_AUDIT_READ("tenant.audit.read", PermissionGroup.AUDIT),

	CATALOG_READ("catalog.read", PermissionGroup.CATALOG, "catalog:read"),
	CATALOG_PRODUCT_MANAGE("catalog.product.manage", PermissionGroup.CATALOG, "catalog:manage"),
	CATALOG_TAXONOMY_MANAGE("catalog.taxonomy.manage", PermissionGroup.CATALOG, "catalog:manage"),
	CATALOG_PRICE_MANAGE("catalog.price.manage", PermissionGroup.CATALOG, "catalog:price:manage"),
	CATALOG_PROMOTION_READ("catalog.promotion.read", PermissionGroup.CATALOG, "promotion:read"),
	CATALOG_PROMOTION_MANAGE("catalog.promotion.manage", PermissionGroup.CATALOG, "promotion:manage"),

	SALES_DASHBOARD_READ("sales.dashboard.read", PermissionGroup.SALES, "sales:read"),
	SALES_PURCHASE_REQUEST_READ("sales.purchase_request.read", PermissionGroup.SALES, "sales:read"),
	SALES_PURCHASE_REQUEST_REVIEW("sales.purchase_request.review", PermissionGroup.SALES, "sales:write"),
	SALES_ORDER_READ("sales.order.read", PermissionGroup.SALES, "sales:read"),
	SALES_ORDER_CREATE_MANUAL("sales.order.create_manual", PermissionGroup.SALES, "sales:write"),
	SALES_ORDER_MANAGE("sales.order.manage", PermissionGroup.SALES, "sales:write"),

	CLIENT_READ("client.read", PermissionGroup.CLIENT_ACCOUNTS, "sales:read"),
	CLIENT_MANAGE("client.manage", PermissionGroup.CLIENT_ACCOUNTS, "sales:write"),
	CLIENT_ADDRESS_MANAGE("client.address.manage", PermissionGroup.CLIENT_ACCOUNTS, "sales:write"),
	CLIENT_COMMERCIAL_TERMS_MANAGE("client.commercial_terms.manage", PermissionGroup.CLIENT_ACCOUNTS, "sales:write"),

	WAREHOUSE_READ("warehouse.read", PermissionGroup.WAREHOUSE, "warehouse:read"),
	WAREHOUSE_LOCATION_MANAGE("warehouse.location.manage", PermissionGroup.WAREHOUSE, "warehouse:write"),
	INVENTORY_READ("inventory.read", PermissionGroup.INVENTORY, "warehouse:read"),
	INVENTORY_RECEIVE("inventory.receive", PermissionGroup.INVENTORY, "warehouse:write"),
	INVENTORY_ADJUST("inventory.adjust", PermissionGroup.INVENTORY, "warehouse:write"),
	INVENTORY_RESERVE("inventory.reserve", PermissionGroup.INVENTORY, "warehouse:write"),
	INVENTORY_RELEASE("inventory.release", PermissionGroup.INVENTORY, "warehouse:write"),
	INVENTORY_WASTE("inventory.waste", PermissionGroup.INVENTORY, "warehouse:write"),

	FULFILLMENT_READ("fulfillment.read", PermissionGroup.FULFILLMENT, "fulfillment:read"),
	FULFILLMENT_MANAGE("fulfillment.manage", PermissionGroup.FULFILLMENT, "warehouse:write"),

	LOGISTICS_READ("logistics.read", PermissionGroup.LOGISTICS, "logistics:read"),
	DISPATCH_READ("dispatch.read", PermissionGroup.LOGISTICS, "logistics:read"),
	DISPATCH_ASSIGN("dispatch.assign", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_SCHEDULE("dispatch.schedule", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_START_ROUTE("dispatch.start_route", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_TEMPERATURE("dispatch.temperature", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_INCIDENT("dispatch.incident", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_REPROGRAM("dispatch.reprogram", PermissionGroup.LOGISTICS, "logistics:write"),
	DISPATCH_COMPLETE("dispatch.complete", PermissionGroup.LOGISTICS, "logistics:write"),
	LOGISTICS_ANALYTICS_READ("logistics.analytics.read", PermissionGroup.ANALYTICS, "logistics:read"),
	DOCUMENT_READ("document.read", PermissionGroup.DOCUMENTS),
	DOCUMENT_GENERATE("document.generate", PermissionGroup.DOCUMENTS),
	DOCUMENT_REGENERATE("document.regenerate", PermissionGroup.DOCUMENTS),
	DOCUMENT_UPLOAD("document.upload", PermissionGroup.DOCUMENTS),
	DOCUMENT_DOWNLOAD("document.download", PermissionGroup.DOCUMENTS),
	PAYMENT_READ("payment.read", PermissionGroup.PAYMENTS),
	PAYMENT_CREATE("payment.create", PermissionGroup.PAYMENTS),
	PAYMENT_RECONCILE("payment.reconcile", PermissionGroup.PAYMENTS),
	CLIENT_CREDIT_MANAGE("client.credit.manage", PermissionGroup.CLIENT_ACCOUNTS),
	ANALYTICS_EXECUTIVE_READ("analytics.executive.read", PermissionGroup.ANALYTICS, "owner:dashboard:read"),

	NOTIFICATION_READ("notification.read", PermissionGroup.NOTIFICATIONS),
	NOTIFICATION_MANAGE_PREFERENCES("notification.manage_preferences", PermissionGroup.NOTIFICATIONS),
	ORDER_EXPORT_READ("order_export.read", PermissionGroup.ORDER_EXPORTS, "sales:read"),

	BUYER_SALES_READ("buyer.sales.read", PermissionGroup.SALES, "sales:buyer:read"),
	BUYER_SALES_WRITE("buyer.sales.write", PermissionGroup.SALES, "sales:buyer:write"),
	BUYER_ORDER_READ("buyer.order.read", PermissionGroup.SALES, "orders:buyer:read"),
	BUYER_TRACKING_READ("buyer.tracking.read", PermissionGroup.LOGISTICS, "tracking:buyer:read"),
	BUYER_PROFILE_WRITE("buyer.profile.write", PermissionGroup.MEMBERS_AND_ROLES, "profile:buyer:write");

	private final String code;
	private final PermissionGroup group;
	private final Set<String> legacyCodes;

	PermissionKey(String code, PermissionGroup group, String... legacyCodes) {
		this.code = code;
		this.group = group;
		this.legacyCodes = Set.of(legacyCodes);
	}

	public String code() { return code; }
	public PermissionGroup group() { return group; }
	public Set<String> legacyCodes() { return legacyCodes; }

	public boolean matches(String candidate) {
		if (candidate == null) return false;
		String normalized = candidate.trim().toLowerCase(Locale.ROOT);
		return code.equals(normalized) || legacyCodes.contains(normalized);
	}

	public static PermissionKey fromCode(String value) {
		if (value == null || value.isBlank()) throw new AccessPolicyViolation("Permission key is required");
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(key -> key.code.equals(normalized)).findFirst()
				.orElseThrow(() -> new AccessPolicyViolation("Unknown permission key"));
	}

	public static PermissionKey fromCodeOrNull(String value) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(key -> key.code.equals(normalized)).findFirst().orElse(null);
	}
}
