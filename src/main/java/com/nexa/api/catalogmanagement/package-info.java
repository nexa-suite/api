@org.springframework.modulith.ApplicationModule(id = "catalogmanagement", allowedDependencies = {
        "shared",
        "tenantmanagement :: access",
        "tenantmanagement :: access-context",
        "tenantmanagement :: membership"
})
package com.nexa.api.catalogmanagement;
