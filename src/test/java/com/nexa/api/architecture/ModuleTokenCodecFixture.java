package com.nexa.api.architecture;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** Supplies the technical token codec when BC01 is bootstrapped independently of the edge. */
@TestConfiguration(proxyBeanMethods = false)
class ModuleTokenCodecFixture {
    @Bean
    SecureRandom secureRandom() { return new SecureRandom(); }

    @Bean
    JwtEncoder jwtEncoder() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return NimbusJwtEncoder.withKeyPair((RSAPublicKey) pair.getPublic(),
                (RSAPrivateKey) pair.getPrivate()).build();
    }
}
