package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;

import java.util.Objects;

/** Canonical server guard for credit configuration and its account directory. */
public final class CreditAccountConfigurationAuthorization {
    private CreditAccountConfigurationAuthorization() { }

    public static void require(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        context.requireSurface(Surface.PLATFORM);
        if (!context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)) {
            throw new AccessPolicyViolation("A BOM or Company Owner role is required for credit configuration");
        }
        context.requirePermission(PermissionKey.CLIENT_CREDIT_CONFIGURATION_MANAGE);
    }
}
