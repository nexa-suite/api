package com.nexa.api.salescommitment.infrastructure.workflow;

import com.nexa.api.salescommitment.application.publicapi.SalesWorkflowCommands;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes the public workflow boundary with the owning use case. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class SalesWorkflowCommandsConfiguration {
    @Bean
    SalesWorkflowCommands salesWorkflowCommands(SalesOrderUseCase salesOrders) {
        return (context, requestId, version, key, note) -> { salesOrders.convert(context, requestId, version, key, note); };
    }
}
