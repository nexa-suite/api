package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.iam.infrastructure.jwt.JwtAuthenticationTokenIssuer;
import com.nexa.api.iam.infrastructure.jwt.RsaKeyMaterial;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(NexaSecurityProperties.class)
public class JwtSecurityConfiguration {
	private static KeyPair testKeyPair;

	@Bean
	KeyPair rsaKeyPair(NexaSecurityProperties properties) {
		if (properties.isAllowEphemeralKeys()) {
			if (testKeyPair == null) testKeyPair = RsaKeyMaterial.generateForTests();
			return testKeyPair;
		}
		return new KeyPair(RsaKeyMaterial.readPublic(properties.getRsaPublicKey()),
				RsaKeyMaterial.readPrivate(properties.getRsaPrivateKey()));
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
	JwtDecoder jwtDecoder(NexaSecurityProperties properties, KeyPair keyPair) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic()).build();
		String issuer = properties.getIssuer();
		String audience = properties.getAudience();
		decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience)));
		return decoder;
	}

	@Bean
	JwtAuthenticationTokenIssuer jwtAuthenticationTokenIssuer(JwtEncoder encoder, NexaSecurityProperties properties, SecureRandom random) {
		return new JwtAuthenticationTokenIssuer(encoder,
				properties.getIssuer(), properties.getAudience(), properties.getAccessTokenTtl(),
				properties.getRefreshTokenTtl(), random);
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
