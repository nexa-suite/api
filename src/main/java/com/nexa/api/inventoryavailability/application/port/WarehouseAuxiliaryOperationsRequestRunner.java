package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyUseCase;
import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionUseCase;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionUseCase;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalScanValidationUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;
import java.util.function.Function;

/** Runs the auxiliary Warehouse HTTP use cases against one verified Tenant session. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface WarehouseAuxiliaryOperationsRequestRunner {
    <T> T execute(CurrentAccessContext context, Function<Operations, T> operation);

    @org.springframework.modulith.NamedInterface("warehouse-operations")
    record Operations(InboundReceivingDiscrepancyUseCase receivingDiscrepancies,
                      PhysicalAllocationSubstitutionUseCase substitutions,
                      PhysicalScanValidationUseCase scanValidation,
                      LotIdentifierResolutionUseCase lotIdentifiers) {
        public Operations {
            Objects.requireNonNull(receivingDiscrepancies);
            Objects.requireNonNull(substitutions);
            Objects.requireNonNull(scanValidation);
            Objects.requireNonNull(lotIdentifiers);
        }
    }
}
