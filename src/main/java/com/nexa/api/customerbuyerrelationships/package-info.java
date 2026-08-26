@org.springframework.modulith.ApplicationModule(
        id = "BC-02-customer-buyer-relationships",
        allowedDependencies = {
                "shared",
                "BC-03-catalog-commercial-policy :: sales-catalog",
                "BC-01-tenant-access-governance :: access",
                "BC-01-tenant-access-governance :: access-context",
                "BC-01-tenant-access-governance :: buyer-memberships",
                "BC-01-tenant-access-governance :: membership"
        })
package com.nexa.api.customerbuyerrelationships;
