package com.nexa.api.inventoryavailability.infrastructure.workflow;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryReservationCommands;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes the public workflow boundary with the owning use case. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class InventoryReservationCommandsConfiguration {
    @Bean
    InventoryReservationCommands inventoryReservationCommands(WarehouseOperationsService warehouse) {
        return (context, orderId, expectedVersion, key, correlation) -> { warehouse.reserve(context, orderId, expectedVersion, key, correlation); };
    }
}
