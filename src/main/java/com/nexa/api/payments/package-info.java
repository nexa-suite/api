@org.springframework.modulith.ApplicationModule(
        id = "BC-08-payments",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-02-customer-buyer-relationships :: customer-relationships",
                "BC-07-credit-receivables :: credit-receivables-public",
                "BC-09-business-documents :: documents-public",
                "shared :: shared-context",
                "shared :: shared-error-primitives",
                "shared :: shared-technical-out"
        })
package com.nexa.api.payments;
