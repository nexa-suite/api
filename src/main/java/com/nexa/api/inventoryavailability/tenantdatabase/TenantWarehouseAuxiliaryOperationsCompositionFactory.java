package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner.Operations;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;

import java.time.Clock;

/** Composes BC-05 auxiliary application use cases from already-bound owner ports. */
@FunctionalInterface
public interface TenantWarehouseAuxiliaryOperationsCompositionFactory {
    Operations bindTo(InboundReceivingDiscrepancyCases discrepancyCases, BusinessEvidenceQuery businessEvidence,
            PhysicalAllocationCommands physicalAllocations,
            PhysicalAllocationSubstitutionRequests substitutions,
            LotIdentifierResolutionQuery lotIdentifiers,
            WarehouseObjectAccess verifiedWarehouseAccess, Clock clock);
}
