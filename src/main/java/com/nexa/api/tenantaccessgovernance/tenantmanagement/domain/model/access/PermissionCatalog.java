package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Canonical typed permission catalog and built-in role policy. */
public final class PermissionCatalog {
	private static final Set<PermissionKey> ALL = Set.copyOf(EnumSet.allOf(PermissionKey.class));
	private static final Set<PermissionKey> COMPANY_OWNER_ENVELOPE = ALL.stream()
			.filter(key -> !key.code().startsWith("tenant."))
			.filter(key -> key != PermissionKey.TENANT_AUDIT_READ)
			.collect(Collectors.toUnmodifiableSet());

	private PermissionCatalog() { }

	public static Set<PermissionKey> all() { return ALL; }

	public static Map<PermissionGroup, Set<PermissionKey>> byGroup() {
		return Arrays.stream(PermissionGroup.values()).collect(Collectors.toUnmodifiableMap(
			group -> group, group -> ALL.stream().filter(key -> key.group() == group).collect(Collectors.toUnmodifiableSet())));
	}

	public static PermissionKey require(String code) { return PermissionKey.fromCode(code); }

	public static Set<PermissionKey> requireAll(Collection<String> codes) {
		Objects.requireNonNull(codes, "Permission codes are required");
		if (codes.isEmpty()) throw new AccessPolicyViolation("At least one permission is required");
		return codes.stream().map(PermissionKey::fromCode).collect(Collectors.toUnmodifiableSet());
	}

	public static Set<String> codes(Collection<PermissionKey> permissions) {
		return permissions.stream().map(PermissionKey::code).collect(Collectors.toUnmodifiableSet());
	}

	public static Set<String> compatibilityCodes(Collection<PermissionKey> permissions) {
		LinkedHashSet<String> values = new LinkedHashSet<>(codes(permissions));
		permissions.forEach(permission -> values.addAll(permission.legacyCodes()));
		return Set.copyOf(values);
	}

	public static Set<PermissionKey> companyOwnerAssignableEnvelope() { return COMPANY_OWNER_ENVELOPE; }

	public static boolean isKnown(String code) { return PermissionKey.fromCodeOrNull(code) != null; }

	public static Set<PermissionKey> forBuiltInRole(MembershipRole role) {
		return switch (Objects.requireNonNull(role, "Membership role is required")) {
			case TENANT_ADMIN -> Set.of(
					PermissionKey.TENANT_ORGANIZATION_READ, PermissionKey.TENANT_ORGANIZATION_MANAGE,
					PermissionKey.TENANT_WORKSPACE_READ, PermissionKey.TENANT_WORKSPACE_MANAGE,
					PermissionKey.TENANT_MEMBER_READ, PermissionKey.TENANT_MEMBER_INVITE,
					PermissionKey.TENANT_MEMBER_MANAGE, PermissionKey.TENANT_ROLE_READ,
					PermissionKey.TENANT_ROLE_MANAGE, PermissionKey.TENANT_ROLE_ASSIGN,
					PermissionKey.TENANT_ROLE_ASSIGN_RESERVED, PermissionKey.TENANT_SECURITY_MANAGE,
					PermissionKey.TENANT_AUDIT_READ, PermissionKey.NOTIFICATION_READ,
					PermissionKey.NOTIFICATION_MANAGE_PREFERENCES);
			case COMPANY_OWNER -> Set.of(
					PermissionKey.TENANT_ORGANIZATION_READ, PermissionKey.TENANT_ORGANIZATION_MANAGE,
					PermissionKey.TENANT_WORKSPACE_READ, PermissionKey.TENANT_MEMBER_READ,
					PermissionKey.TENANT_MEMBER_INVITE, PermissionKey.TENANT_MEMBER_MANAGE,
					PermissionKey.TENANT_ROLE_READ, PermissionKey.TENANT_ROLE_ASSIGN,
					PermissionKey.CATALOG_READ, PermissionKey.CATALOG_PRODUCT_MANAGE,
					PermissionKey.CATALOG_TAXONOMY_MANAGE, PermissionKey.CATALOG_PRICE_MANAGE,
					PermissionKey.CATALOG_PROMOTION_READ, PermissionKey.CATALOG_PROMOTION_MANAGE,
					PermissionKey.SALES_DASHBOARD_READ, PermissionKey.SALES_PURCHASE_REQUEST_READ,
					PermissionKey.SALES_ORDER_READ, PermissionKey.CLIENT_READ, PermissionKey.CLIENT_MANAGE,
					PermissionKey.CLIENT_ADDRESS_MANAGE, PermissionKey.CLIENT_COMMERCIAL_TERMS_MANAGE,
					PermissionKey.CLIENT_CREDIT_MANAGE, PermissionKey.DOCUMENT_READ,
					PermissionKey.DOCUMENT_GENERATE, PermissionKey.DOCUMENT_REGENERATE,
					PermissionKey.DOCUMENT_UPLOAD, PermissionKey.DOCUMENT_DOWNLOAD,
					PermissionKey.PAYMENT_READ, PermissionKey.PAYMENT_CREATE,
					PermissionKey.PAYMENT_RECONCILE,
					PermissionKey.ANALYTICS_EXECUTIVE_READ, PermissionKey.ORDER_EXPORT_READ,
					PermissionKey.NOTIFICATION_READ, PermissionKey.NOTIFICATION_MANAGE_PREFERENCES);
			case SALES -> Set.of(
					PermissionKey.CATALOG_READ,
					PermissionKey.CATALOG_PROMOTION_READ, PermissionKey.SALES_DASHBOARD_READ,
					PermissionKey.SALES_PURCHASE_REQUEST_READ, PermissionKey.SALES_PURCHASE_REQUEST_REVIEW,
					PermissionKey.SALES_ORDER_READ, PermissionKey.SALES_ORDER_CREATE_MANUAL,
					PermissionKey.SALES_ORDER_MANAGE, PermissionKey.CLIENT_READ, PermissionKey.CLIENT_MANAGE,
					PermissionKey.CLIENT_ADDRESS_MANAGE, PermissionKey.ORDER_EXPORT_READ,
					PermissionKey.DOCUMENT_READ, PermissionKey.DOCUMENT_DOWNLOAD,
					PermissionKey.PAYMENT_READ, PermissionKey.NOTIFICATION_READ);
			case WAREHOUSE -> Set.of(
					PermissionKey.CATALOG_READ, PermissionKey.WAREHOUSE_READ,
					PermissionKey.INVENTORY_READ, PermissionKey.INVENTORY_RECEIVE,
					PermissionKey.INVENTORY_ADJUST, PermissionKey.INVENTORY_RESERVE,
					PermissionKey.INVENTORY_RELEASE, PermissionKey.INVENTORY_WASTE,
					PermissionKey.FULFILLMENT_READ, PermissionKey.FULFILLMENT_MANAGE,
					PermissionKey.DOCUMENT_READ, PermissionKey.DOCUMENT_UPLOAD,
					PermissionKey.NOTIFICATION_READ);
			case LOGISTICS -> Set.of(
					PermissionKey.CATALOG_READ, PermissionKey.CATALOG_PROMOTION_READ, PermissionKey.WAREHOUSE_READ,
					PermissionKey.FULFILLMENT_READ,
					PermissionKey.LOGISTICS_READ, PermissionKey.DISPATCH_READ,
					PermissionKey.DISPATCH_ASSIGN, PermissionKey.DISPATCH_SCHEDULE,
					PermissionKey.DISPATCH_START_ROUTE, PermissionKey.DISPATCH_TEMPERATURE,
					PermissionKey.DISPATCH_INCIDENT, PermissionKey.DISPATCH_REPROGRAM,
					PermissionKey.DISPATCH_COMPLETE, PermissionKey.LOGISTICS_ANALYTICS_READ,
					PermissionKey.DOCUMENT_READ, PermissionKey.DOCUMENT_UPLOAD,
					PermissionKey.NOTIFICATION_READ);
			case BUYER -> Set.of(
					PermissionKey.CATALOG_READ, PermissionKey.CATALOG_PROMOTION_READ,
					PermissionKey.BUYER_SALES_READ, PermissionKey.BUYER_SALES_WRITE,
					PermissionKey.BUYER_ORDER_READ, PermissionKey.BUYER_TRACKING_READ, PermissionKey.NOTIFICATION_READ,
					PermissionKey.BUYER_PROFILE_WRITE, PermissionKey.DOCUMENT_READ,
					PermissionKey.DOCUMENT_DOWNLOAD, PermissionKey.PAYMENT_READ,
					PermissionKey.PAYMENT_CREATE);
		};
	}

	public static Set<PermissionKey> forBuiltInRoles(Collection<MembershipRole> roles) {
		if (roles == null || roles.isEmpty()) throw new AccessPolicyViolation("At least one membership role is required");
		EnumSet<PermissionKey> result = EnumSet.noneOf(PermissionKey.class);
		roles.forEach(role -> result.addAll(forBuiltInRole(role)));
		return Set.copyOf(result);
	}

	public static Set<PermissionKey> parseCodes(Collection<String> codes) {
		return requireAll(codes);
	}

	public static String normalizeCode(String code) {
		if (code == null || code.isBlank()) throw new AccessPolicyViolation("Permission key is required");
		return code.trim().toLowerCase(Locale.ROOT);
	}
}
