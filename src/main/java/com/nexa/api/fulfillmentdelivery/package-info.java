@org.springframework.modulith.ApplicationModule(
        id = "BC-06-fulfillment-delivery",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-01-tenant-access-governance :: governance-queries",
                "BC-02-customer-buyer-relationships :: customer-relationships",
                "BC-03-catalog-commercial-policy :: sales-catalog",
                "BC-04-sales-commitment :: sales-public",
                "BC-04-sales-commitment :: salescommitment-errors",
                "BC-05-inventory-availability :: sales-availability",
                "BC-07-credit-receivables :: credit-receivables-public",
                "BC-09-business-documents :: documents-public",
                "BC-11-business-traceability :: traceability-public",
                "shared :: shared-technical-out"
        })
package com.nexa.api.fulfillmentdelivery;
