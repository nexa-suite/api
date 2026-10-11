package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;

/** Reads whether a built-in membership role grants a canonical permission. */
@FunctionalInterface
public interface BuiltInRolePermissionQuery {
    boolean allows(MembershipRole role, PermissionKey permission);
}
