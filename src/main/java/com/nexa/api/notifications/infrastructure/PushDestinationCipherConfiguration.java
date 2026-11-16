package com.nexa.api.notifications.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Loads Tenant push-destination keys from application configuration, never from a database. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "true")
public class PushDestinationCipherConfiguration {
    @Bean
    PushDestinationCipher pushDestinationCipher(
            @Value("${nexa.notifications.push.destination-key:}") String currentKey,
            @Value("${nexa.notifications.push.destination-key-version:v1}") String currentKeyVersion,
            @Value("${nexa.notifications.push.destination-previous-keys:}") String previousKeys) {
        return new PushDestinationCipher(currentKey, currentKeyVersion, previousKeys);
    }
}
