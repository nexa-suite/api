/** BC-07 Credit & Receivables. Payment provider lifecycle remains in BC-08. */
@org.springframework.modulith.ApplicationModule(
        id = "BC-07-credit-receivables",
        allowedDependencies = {
                "BC-01-tenant-access-governance :: access-contracts",
                "BC-01-tenant-access-governance :: access-values",
                "BC-02-customer-buyer-relationships :: customer-relationships",
                "BC-11-business-traceability :: traceability-public",
                "shared :: shared-technical-out"
        })
package com.nexa.api.creditreceivables;
