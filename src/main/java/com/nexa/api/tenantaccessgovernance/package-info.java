/**
 * BC-01 Tenant & Access Governance.
 *
 * Tenant administration and IAM remain technical subpackages of this single
 * bounded context; they are not separate business contexts.
 */
@org.springframework.modulith.ApplicationModule(
        id = "BC-01-tenant-access-governance",
        allowedDependencies = {
                "shared :: shared-context",
                "shared :: shared-error-primitives",
                "shared :: shared-technical-out"
        })
package com.nexa.api.tenantaccessgovernance;
