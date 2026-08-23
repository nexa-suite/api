@org.springframework.modulith.ApplicationModule(
        id = "customerrelationships",
        allowedDependencies = {
                "shared",
                "tenantmanagement :: access",
                "tenantmanagement :: access-context",
                "tenantmanagement :: membership"
        })
package com.nexa.api.customerrelationships;
