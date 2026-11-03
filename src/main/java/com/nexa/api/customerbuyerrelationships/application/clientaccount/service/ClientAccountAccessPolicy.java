package com.nexa.api.customerbuyerrelationships.application.clientaccount.service;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;

import java.util.Objects;

/** Application authorization shared by Customer Relationship account entry points. */
@org.springframework.modulith.NamedInterface("customer-relationships")
public final class ClientAccountAccessPolicy {
    private ClientAccountAccessPolicy() { }

    public static void requireRead(CurrentAccessContext context) {
        requireContext(context);
        denyBuyerAndUnqualifiedTenantAdmin(context);
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) return;
        context.requirePermission(PermissionKey.CLIENT_READ);
    }

    public static void requireManage(CurrentAccessContext context) {
        requireContext(context);
        denyBuyerAndUnqualifiedTenantAdmin(context);
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            throw new AccessPolicyViolation("Business Operations Manager has read-only Buyer account audit access");
        }
        context.requirePermission(PermissionKey.CLIENT_MANAGE);
    }

    public static void requireBuyerCandidateRead(CurrentAccessContext context) {
        requireContext(context);
        if (!context.hasRole(MembershipRole.COMPANY_OWNER)) {
            throw new AccessPolicyViolation("Buyer membership candidates are restricted to Company Owner account administration");
        }
        context.requirePermission(PermissionKey.CLIENT_READ);
    }

    public static void requireSalesOwnerAssignment(CurrentAccessContext context) {
        requireContext(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
        }
        context.requireSurface(Surface.PLATFORM);
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) return;
        if (context.hasRole(MembershipRole.COMPANY_OWNER)) {
            context.requirePermission(PermissionKey.CLIENT_MANAGE);
            return;
        }
        throw new AccessPolicyViolation("Seller assignment is restricted to Company Owner and Business Operations Manager");
    }

    public static void requireAddressAccess(CurrentAccessContext context, boolean write) {
        requireContext(context);
        denyBuyerAndUnqualifiedTenantAdmin(context);
        if (write) context.requirePermission(PermissionKey.CLIENT_ADDRESS_MANAGE);
        else requireRead(context);
    }

    public static boolean isSalesOwnerScoped(CurrentAccessContext context) {
        return context.hasRole(MembershipRole.SALES)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
    }

    private static void denyBuyerAndUnqualifiedTenantAdmin(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
        }
        if (context.hasRole(MembershipRole.TENANT_ADMIN)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)
                && !context.hasRole(MembershipRole.SALES)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            throw new AccessPolicyViolation("Tenant administration does not grant access to Buyer account data");
        }
    }

    private static void requireContext(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
    }
}
