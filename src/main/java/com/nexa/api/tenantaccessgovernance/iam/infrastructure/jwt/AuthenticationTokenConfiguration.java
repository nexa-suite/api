package com.nexa.api.tenantaccessgovernance.iam.infrastructure.jwt;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import java.security.SecureRandom;
import java.time.Duration;

/** IAM owns token issuance; the edge supplies the technical encoder. */
@Configuration(proxyBeanMethods = false)
class AuthenticationTokenConfiguration {
    @Bean
    JwtAuthenticationTokenIssuer jwtAuthenticationTokenIssuer(JwtEncoder encoder, Environment environment, SecureRandom random) {
        Binder binder = Binder.get(environment);
        return new JwtAuthenticationTokenIssuer(encoder,
                environment.getProperty("nexa.security.issuer"), environment.getProperty("nexa.security.audience"),
                binder.bind("nexa.security.access-token-ttl", Duration.class).orElse(Duration.ofMinutes(15)),
                binder.bind("nexa.security.refresh-token-ttl", Duration.class).orElse(Duration.ofDays(30)), random);
    }
}
