@org.springframework.modulith.ApplicationModule(
        id = "customerrelationships",
        allowedDependencies = {
                "shared",
                "catalogmanagement :: sales-catalog",
                "tenantmanagement :: access",
                "tenantmanagement :: access-context",
                "tenantmanagement :: buyer-memberships",
                "tenantmanagement :: membership"
        })
package com.nexa.api.customerrelationships;
