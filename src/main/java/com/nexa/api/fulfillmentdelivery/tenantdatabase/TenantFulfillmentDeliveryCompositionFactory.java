package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryTemperatureHoldCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.NamedInterface;

import java.util.Objects;

/** Assembles BC-06 services from owner-published business ports already bound to one Tenant session. */
@NamedInterface("fulfillment-tenant-composition")
@FunctionalInterface
public interface TenantFulfillmentDeliveryCompositionFactory {
    FulfillmentDeliveryComposition bindTo(JdbcTemplate tenantJdbc,
                                          WarehouseObjectAccess verifiedWarehouseAccess,
                                          WorkforceDirectory verifiedWorkforce,
                                          Bindings bindings);

    @NamedInterface("fulfillment-tenant-composition")
    record Bindings(CustomerAccountQuery customerAccounts,
                    SalesOrderFulfillmentQuery salesOrderQueries,
                    SalesOrderFulfillmentCommands salesOrderCommands,
                    PhysicalAllocationCommands physicalAllocations,
                    InventoryBackingQuery inventoryBackings,
                    ColdChainPolicyQuery coldChainPolicies,
                    InventoryTemperatureHoldCommands temperatureHolds,
                    WarehouseSelectionQuery warehouseSelections,
                    WarehouseLogisticsFulfillmentPort warehouseLogistics,
                    WarehouseEventContextQueryPort warehouseEvents,
                    BusinessEvidenceQuery businessEvidence,
                    ChangeEventPersistencePort changeFeed,
                    FinancialAdjustmentCommands financialAdjustments,
                    BusinessTraceabilityCommands traceabilityCommands,
                    CanonicalOutboxPort canonicalOutbox) {
        public Bindings {
            Objects.requireNonNull(customerAccounts, "Tenant customer account query is required");
            Objects.requireNonNull(salesOrderQueries, "Tenant sales order query is required");
            Objects.requireNonNull(salesOrderCommands, "Tenant sales order commands are required");
            Objects.requireNonNull(physicalAllocations, "Tenant physical allocation commands are required");
            Objects.requireNonNull(inventoryBackings, "Tenant inventory backing query is required");
            Objects.requireNonNull(coldChainPolicies, "Tenant cold-chain policy query is required");
            Objects.requireNonNull(temperatureHolds, "Tenant temperature hold commands are required");
            Objects.requireNonNull(warehouseSelections, "Tenant warehouse selection query is required");
            Objects.requireNonNull(warehouseLogistics, "Tenant warehouse logistics port is required");
            Objects.requireNonNull(warehouseEvents, "Tenant warehouse event query is required");
            Objects.requireNonNull(businessEvidence, "Tenant business evidence query is required");
            Objects.requireNonNull(changeFeed, "Tenant change feed is required");
            Objects.requireNonNull(financialAdjustments, "Tenant financial adjustment commands are required");
            Objects.requireNonNull(traceabilityCommands, "Tenant traceability commands are required");
            Objects.requireNonNull(canonicalOutbox, "Tenant canonical outbox is required");
        }
    }
}
