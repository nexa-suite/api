@org.springframework.modulith.ApplicationModule(
        id = "BC-10-notifications",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-02-customer-buyer-relationships :: customer-relationships",
                "shared :: shared-error-primitives",
                "shared :: shared-technical-out"
        })
package com.nexa.api.notifications;
