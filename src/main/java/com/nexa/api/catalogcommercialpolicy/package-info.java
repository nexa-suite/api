@org.springframework.modulith.ApplicationModule(
        id = "BC-03-catalog-commercial-policy",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "shared :: shared-context",
                "shared :: shared-error-primitives"
        })
package com.nexa.api.catalogcommercialpolicy;
