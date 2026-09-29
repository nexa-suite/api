@org.springframework.modulith.ApplicationModule(
        id = "BC-05-inventory-availability",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-03-catalog-commercial-policy :: catalog-availability-source",
                "BC-03-catalog-commercial-policy :: catalog-snapshots",
                "BC-03-catalog-commercial-policy :: sales-catalog",
                "BC-11-business-traceability :: traceability-public",
                "shared :: shared-context",
                "shared :: shared-technical-out"
        })
package com.nexa.api.inventoryavailability;
