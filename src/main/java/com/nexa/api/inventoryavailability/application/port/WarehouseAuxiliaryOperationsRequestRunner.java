package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.service.InboundReceivingDiscrepancyService;
import com.nexa.api.inventoryavailability.application.service.LotIdentifierResolutionService;
import com.nexa.api.inventoryavailability.application.service.PhysicalAllocationSubstitutionService;
import com.nexa.api.inventoryavailability.application.service.PhysicalScanValidationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;
import java.util.function.Function;

/** Runs the auxiliary Warehouse HTTP use cases against one verified Tenant session. */
public interface WarehouseAuxiliaryOperationsRequestRunner {
    <T> T execute(CurrentAccessContext context, Function<Operations, T> operation);

    record Operations(InboundReceivingDiscrepancyService receivingDiscrepancies,
                      PhysicalAllocationSubstitutionService substitutions,
                      PhysicalScanValidationService scanValidation,
                      LotIdentifierResolutionService lotIdentifiers) {
        public Operations {
            Objects.requireNonNull(receivingDiscrepancies);
            Objects.requireNonNull(substitutions);
            Objects.requireNonNull(scanValidation);
            Objects.requireNonNull(lotIdentifiers);
        }
    }
}
