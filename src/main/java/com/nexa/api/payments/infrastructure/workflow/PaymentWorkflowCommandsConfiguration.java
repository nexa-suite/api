package com.nexa.api.payments.infrastructure.workflow;

import com.nexa.api.payments.application.publicapi.PaymentWorkflowCommands;
import com.nexa.api.payments.application.port.PaymentPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes the public workflow boundary with the owning use case. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class PaymentWorkflowCommandsConfiguration {
    @Bean
    PaymentWorkflowCommands paymentWorkflowCommands(PaymentPort payments) {
        return (context, subjectType, subjectId, key) -> { payments.createReceivable(context, new PaymentPort.ReceivableCommand(subjectType, subjectId, null, key)); };
    }
}
