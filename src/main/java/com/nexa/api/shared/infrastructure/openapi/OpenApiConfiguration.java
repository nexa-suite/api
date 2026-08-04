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
				.info(new Info().title("Nexa API").version("0.8.1").description(
						"Local API contract for secured identity, tenant/workspace administration, Catalog reads and "
								+ "Sales Client Accounts and Purchase Requests. Bearer authentication revalidates the "
								+ "persistent session; refresh and sign-out require the surface refresh cookie and Origin policy. "
								+ "Sales Order conversion and lifecycle endpoints plus the tenant-scoped persisted change feed are included. "
						+ "Warehouse and Logistics HTTP workflows are included with FEFO, dispatch lifecycle and Proof of Delivery evidence. "
						+ "Business Documents, private object storage, invoice drafts, receivables and Stripe-compatible payment intents are included."))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
								.scheme("bearer").bearerFormat("JWT").description("RS256 access token"))
						.addSecuritySchemes("refreshCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.COOKIE).name("NEXA_PLATFORM_REFRESH")
								.description("HttpOnly SameSite=Strict refresh cookie; Portal uses NEXA_PORTAL_REFRESH")));
	}
}
