package com.nexa.api.inventoryavailability.infrastructure.workflow;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner.Operations;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests;
import com.nexa.api.inventoryavailability.application.service.InboundReceivingDiscrepancyService;
import com.nexa.api.inventoryavailability.application.service.LotIdentifierResolutionService;
import com.nexa.api.inventoryavailability.application.service.PhysicalAllocationSubstitutionService;
import com.nexa.api.inventoryavailability.application.service.PhysicalScanValidationService;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseAuxiliaryOperationsCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

/** Builds request-local BC-05 use cases while routing and transaction ownership stay with bootstrap. */
@Component
@Profile("!test")
public final class JdbcTenantWarehouseAuxiliaryOperationsCompositionFactory
        implements TenantWarehouseAuxiliaryOperationsCompositionFactory {
    @Override
    public Operations bindTo(InboundReceivingDiscrepancyCases discrepancyCases,
            BusinessEvidenceQuery businessEvidence, PhysicalAllocationCommands physicalAllocations,
            PhysicalAllocationSubstitutionRequests substitutions, LotIdentifierResolutionQuery lotIdentifiers,
            WarehouseObjectAccess verifiedWarehouseAccess, Clock clock) {
        Clock verifiedClock = Objects.requireNonNull(clock, "Clock is required");
        WarehouseObjectAccess warehouseAccess = Objects.requireNonNull(verifiedWarehouseAccess,
                "Verified Warehouse access snapshot is required");
        return new Operations(
                new InboundReceivingDiscrepancyService(
                        Objects.requireNonNull(discrepancyCases, "Tenant discrepancy cases are required"),
                        Objects.requireNonNull(businessEvidence, "Tenant business evidence query is required"),
                        verifiedClock),
                new PhysicalAllocationSubstitutionService(
                        Objects.requireNonNull(substitutions, "Tenant substitution requests are required"), verifiedClock),
                new PhysicalScanValidationService(
                        Objects.requireNonNull(physicalAllocations, "Tenant physical allocations are required"),
                        verifiedClock, warehouseAccess),
                new LotIdentifierResolutionService(
                        Objects.requireNonNull(lotIdentifiers, "Tenant lot identifier query is required"),
                        warehouseAccess));
    }
}
