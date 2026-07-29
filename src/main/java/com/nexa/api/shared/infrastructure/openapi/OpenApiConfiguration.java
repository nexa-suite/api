package com.nexa.api.shared.infrastructure.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class OpenApiConfiguration {
	@Bean
	OpenAPI nexaOpenAPI() {
		return new OpenAPI()
				.info(new Info().title("Nexa API").version("0.5.0").description(
						"Local API contract for secured identity, tenant workspace access and catalog reads. "
								+ "There is no public sign-up, user-management or catalog-write endpoint in this release."))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
								.scheme("bearer").bearerFormat("JWT").description("RS256 access token"))
						.addSecuritySchemes("refreshCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.COOKIE).name("NEXA_PLATFORM_REFRESH")
								.description("HttpOnly SameSite=Strict refresh cookie; Portal uses NEXA_PORTAL_REFRESH")));
	}
}
