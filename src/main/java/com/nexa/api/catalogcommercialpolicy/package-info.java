@org.springframework.modulith.ApplicationModule(id = "BC-03-catalog-commercial-policy", allowedDependencies = {
        "shared",
        "shared :: shared-error-primitives",
        "BC-01-tenant-access-governance :: access",
        "BC-01-tenant-access-governance :: access-context",
        "BC-01-tenant-access-governance :: membership"
})
package com.nexa.api.catalogcommercialpolicy;
