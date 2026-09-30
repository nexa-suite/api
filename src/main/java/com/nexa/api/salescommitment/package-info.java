@org.springframework.modulith.ApplicationModule(
        id = "BC-04-sales-commitment",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-02-customer-buyer-relationships :: customer-relationships",
                "BC-03-catalog-commercial-policy :: catalog-snapshots",
                "BC-03-catalog-commercial-policy :: sales-catalog",
                "BC-05-inventory-availability :: sales-availability",
                "BC-07-credit-receivables :: credit-receivables-public",
                "BC-08-payments :: payments-public",
                "shared :: shared-technical-out"
        })
package com.nexa.api.salescommitment;
