package com.nexa.api.fulfillmentdelivery.infrastructure.workflow;

import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchWorkflowCommands;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes the public workflow boundary with the owning use case. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class DispatchWorkflowCommandsConfiguration {
    @Bean
    DispatchWorkflowCommands dispatchWorkflowCommands(LogisticsOperationsService logistics) {
        return (context, reservationId, version, key) -> { logistics.create(context, reservationId, version, key); };
    }
}
