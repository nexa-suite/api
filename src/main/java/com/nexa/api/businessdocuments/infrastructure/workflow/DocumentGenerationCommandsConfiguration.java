package com.nexa.api.businessdocuments.infrastructure.workflow;

import com.nexa.api.businessdocuments.application.publicapi.DocumentGenerationCommands;
import com.nexa.api.businessdocuments.application.port.BusinessDocumentPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composes the public workflow boundary with the owning use case. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class DocumentGenerationCommandsConfiguration {
    @Bean
    DocumentGenerationCommands documentGenerationCommands(BusinessDocumentPort documents) {
        return (context, subjectType, subjectId, documentType, format, key) -> { documents.request(context, subjectType, subjectId, documentType, format, key); };
    }
}
