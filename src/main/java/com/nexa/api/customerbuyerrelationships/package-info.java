@org.springframework.modulith.ApplicationModule(
        id = "BC-02-customer-buyer-relationships",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-03-catalog-commercial-policy :: sales-catalog",
                "shared :: shared-error-primitives"
        })
package com.nexa.api.customerbuyerrelationships;
