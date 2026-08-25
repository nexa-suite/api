package com.nexa.api.shared.infrastructure.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class OpenApiConfiguration {
	@Bean
	OpenAPI nexaOpenAPI(ObjectProvider<BuildProperties> buildProperties,
			@Value("${spring.application.version:unknown}") String configuredVersion) {
		BuildProperties packagedBuild = buildProperties.getIfAvailable();
		String version = packagedBuild == null ? configuredVersion : packagedBuild.getVersion();
		return new OpenAPI()
				.info(new Info().title("Nexa API").version(version).description(
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

	@Bean
	GlobalOpenApiCustomizer nexaTechnicalErrorContract() {
		return openAPI -> {
			Components components = openAPI.getComponents() == null ? new Components() : openAPI.getComponents();
			openAPI.setComponents(components);
			components.addSchemas("NexaProblemDetail", problemSchema());
			Map<String, String> statuses = Map.ofEntries(
					Map.entry("400", "Request or validation error"),
					Map.entry("401", "Authentication required"),
					Map.entry("403", "Access denied"),
					Map.entry("404", "Resource not found"),
					Map.entry("409", "Concurrency or conflict error"),
					Map.entry("412", "Precondition failed"),
					Map.entry("428", "Precondition required"),
					Map.entry("429", "Rate limit exceeded"),
					Map.entry("500", "Unexpected server error"),
					Map.entry("502", "External technical dependency failed"),
					Map.entry("503", "Technical capability unavailable"),
					Map.entry("504", "External technical dependency timed out"));
			openAPI.getPaths().values().stream()
					.flatMap(path -> path.readOperations().stream())
					.forEach(operation -> addTechnicalResponses(operation, statuses));
		};
	}

	private static Schema<?> problemSchema() {
		ObjectSchema schema = new ObjectSchema();
		schema.addProperty("type", new StringSchema().format("uri"));
		schema.addProperty("title", new StringSchema());
		schema.addProperty("status", new IntegerSchema().format("int32"));
		schema.addProperty("detail", new StringSchema());
		schema.addProperty("instance", new StringSchema().format("uri"));
		schema.addProperty("code", new StringSchema());
		schema.addProperty("correlationId", new StringSchema());
		schema.addProperty("category", new StringSchema());
		schema.addProperty("retryable", new BooleanSchema());
		schema.addProperty("traceId", new StringSchema());
		schema.addProperty("errors", new ArraySchema().items(new ObjectSchema()));
		schema.setRequired(List.of("code", "correlationId", "category", "retryable"));
		return schema;
	}

	private static void addTechnicalResponses(Operation operation, Map<String, String> statuses) {
		ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
		operation.setResponses(responses);
		statuses.forEach((status, description) -> {
			if (!responses.containsKey(status)) {
				ApiResponse response = new ApiResponse().description(description)
						.content(new io.swagger.v3.oas.models.media.Content()
								.addMediaType("application/problem+json", problemMediaType())
								.addMediaType("application/json", problemMediaType()));
				if ("429".equals(status)) {
					response.addHeaderObject("Retry-After", new Header().description("Seconds before retrying").schema(new IntegerSchema().format("int32")));
				}
				responses.addApiResponse(status, response);
			}
		});
	}

	private static MediaType problemMediaType() {
		return new MediaType().schema(new Schema<>().$ref("#/components/schemas/NexaProblemDetail"));
	}
}
