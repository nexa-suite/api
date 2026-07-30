package com.nexa.api.shared.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ApiSecurityConfiguration {
	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, Environment environment,
			AuthenticationEntryPoint authenticationEntryPoint, AccessDeniedHandler accessDeniedHandler,
			ObjectMapper objectMapper, JwtAuthenticationConverter jwtAuthenticationConverter,
			ObjectProvider<CurrentAccessContextFilter> currentAccessContextFilter) throws Exception {
		boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));
		Set<String> allowedOrigins = allowedOrigins(environment);
			http.csrf(AbstractHttpConfigurer::disable)
				.headers(headers -> {
					headers.contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"));
					headers.contentTypeOptions(content -> { });
					headers.referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
					headers.permissionsPolicy(permissions -> permissions.policy("camera=(), microphone=(), geolocation=()"));
					if (!localProfile) headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).preload(true));
				})
				.cors(cors -> cors.configurationSource(corsConfigurationSource(environment)))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new CookieOriginGuardFilter(objectMapper, allowedOrigins), BearerTokenAuthenticationFilter.class)
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(authenticationEntryPoint))
				.authorizeHttpRequests(authorize -> {
					authorize.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
					if (localProfile) authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
					authorize.requestMatchers("/api/v1/authentication/sign-in", "/api/v1/authentication/refresh",
						"/api/v1/authentication/sign-out").permitAll();
					authorize.requestMatchers("/api/**").authenticated();
					authorize.anyRequest().denyAll();
					});
		CurrentAccessContextFilter accessFilter = currentAccessContextFilter.getIfAvailable();
		if (accessFilter != null) http.addFilterAfter(accessFilter, BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(Environment environment) {
		var configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.copyOf(allowedOrigins(environment)));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Correlation-Id", "X-Nexa-Surface"));
		configuration.setExposedHeaders(List.of("ETag", "X-Correlation-Id"));
		configuration.setAllowCredentials(true);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	private static Set<String> allowedOrigins(Environment environment) {
		return Arrays.stream(environment.getProperty("nexa.security.allowed-origins", "http://localhost:4200,http://localhost:4300").split(","))
				.map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Bean
	ProblemDetailAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
		return new ProblemDetailAuthenticationEntryPoint(objectMapper);
	}

	@Bean
	ProblemDetailAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return new ProblemDetailAccessDeniedHandler(objectMapper);
	}

	@Bean
	@org.springframework.context.annotation.Profile("!test")
	CurrentAccessContextFilter currentAccessContextFilter(
			com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase accessContext,
			com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase accessSession,
			AuthenticationEntryPoint authenticationEntryPoint, AccessTokenInvalidEntryPoint accessTokenInvalidEntryPoint,
			AccessContextInvalidHandler accessContextInvalidHandler) {
		return new CurrentAccessContextFilter(accessContext, accessSession, authenticationEntryPoint,
				accessTokenInvalidEntryPoint, accessContextInvalidHandler);
	}

	@Bean
	AccessContextInvalidHandler accessContextInvalidHandler(ObjectMapper objectMapper) {
		return new AccessContextInvalidHandler(objectMapper);
	}

	@Bean
	AccessTokenInvalidEntryPoint accessTokenInvalidEntryPoint(ObjectMapper objectMapper) {
		return new AccessTokenInvalidEntryPoint(objectMapper);
	}
}
