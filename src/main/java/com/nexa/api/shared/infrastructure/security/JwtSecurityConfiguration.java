package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.iam.infrastructure.jwt.JwtAuthenticationTokenIssuer;
import com.nexa.api.iam.infrastructure.jwt.RsaKeyMaterial;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.security.SecureRandom;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class JwtSecurityConfiguration {
	private static KeyPair testKeyPair;

	@Bean
	KeyPair rsaKeyPair(Environment environment) {
		if (environment.getProperty("nexa.security.allow-ephemeral-keys", Boolean.class, false)) {
			if (testKeyPair == null) testKeyPair = RsaKeyMaterial.generateForTests();
			return testKeyPair;
		}
		return new KeyPair(RsaKeyMaterial.readPublic(environment.getProperty("nexa.security.rsa-public-key", "./.local-keys/access-token-public.pem")),
				RsaKeyMaterial.readPrivate(environment.getProperty("nexa.security.rsa-private-key", "./.local-keys/access-token-private.pem")));
	}

	@Bean
	SecureRandom secureRandom() {
		return new SecureRandom();
	}

	@Bean
	JwtEncoder jwtEncoder(KeyPair keyPair) {
		return NimbusJwtEncoder.withKeyPair((java.security.interfaces.RSAPublicKey) keyPair.getPublic(),
				(java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()).build();
	}

	@Bean
	JwtDecoder jwtDecoder(Environment environment, KeyPair keyPair) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic()).build();
		String issuer = environment.getProperty("nexa.security.issuer", "http://localhost:8080");
		String audience = environment.getProperty("nexa.security.audience", "nexa-local");
		decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience)));
		return decoder;
	}

	@Bean
	JwtAuthenticationTokenIssuer jwtAuthenticationTokenIssuer(JwtEncoder encoder, Environment environment, SecureRandom random) {
		return new JwtAuthenticationTokenIssuer(encoder,
				environment.getProperty("nexa.security.issuer", "http://localhost:8080"),
				environment.getProperty("nexa.security.audience", "nexa-local"),
				Duration.parse(environment.getProperty("nexa.security.access-token-ttl", "PT15M")),
				Duration.parse(environment.getProperty("nexa.security.refresh-token-ttl", "P30D")), random);
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("permissions");
		authorities.setAuthorityPrefix("");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		converter.setPrincipalClaimName("sub");
		return converter;
	}

	private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
		return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The JWT audience is invalid", null));
	}
}
