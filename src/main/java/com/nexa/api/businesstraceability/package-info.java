@org.springframework.modulith.ApplicationModule(
        id = "BC-11-business-traceability",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "shared :: shared-error-primitives",
                "shared :: shared-technical-out"
        })
package com.nexa.api.businesstraceability;
