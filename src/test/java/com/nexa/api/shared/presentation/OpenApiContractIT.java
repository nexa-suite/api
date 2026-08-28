package com.nexa.api.shared.presentation;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OpenApiContractIT extends NexaWorkflowIntegrationSupport {
    @Test void runtimeOpenApiContainsWarehouseAndLogisticsContracts() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = json(result);
        assertThat(document.get("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.get("paths").has("/api/v1/warehouses/{warehouseId}/zones/{zoneId}")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/profile")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/location")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/hours")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/serviceability")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/selection-policy")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/warehouses")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/route-starts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/handoff-notes")).isTrue();
        assertThat(document.get("paths").has("/api/v1/my-deliveries/{id}/events")).isTrue();
        assertThat(document.get("paths").has("/api/v1/skus/resolve")).isTrue();
        assertThat(document.get("paths").has("/api/v1/inventory/lots/resolve")).isTrue();
        assertThat(document.get("paths").has("/api/v1/inventory/physical-allocation-scan-validations")).isTrue();
        assertThat(document.get("paths").has("/api/v1/deliveries/{deliveryId}/handoff-tokens")).isTrue();
        assertThat(document.get("paths").has("/api/v1/delivery-handoff/validations")).isTrue();
        assertThat(document.get("paths").has("/api/v1/deliveries/{deliveryId}/buyer-receipts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions/{subscriptionId}/disable")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions/{subscriptionId}")).isTrue();
        assertRequiredHeader(document, "/api/v1/deliveries/{deliveryId}/handoff-tokens", "post", "Idempotency-Key");
        assertRequiredHeader(document, "/api/v1/deliveries/{deliveryId}/buyer-receipts", "post", "Idempotency-Key");
        assertRequiredHeader(document, "/api/v1/notifications/push-subscriptions", "post", "X-Nexa-Client");
        assertRequiredHeader(document, "/api/v1/notifications/push-subscriptions", "post", "Idempotency-Key");
        assertThat(document.at("/components/securitySchemes/nativeRefreshToken/name").asText())
                .isEqualTo("X-Nexa-Refresh-Token");
        var refresh = document.get("paths").get("/api/v1/authentication/refresh").get("post");
        assertThat(refresh.get("security").toString()).contains("refreshCookie", "nativeRefreshToken");

        var problem = document.at("/components/schemas/NexaProblemDetail");
        assertThat(problem.isObject()).isTrue();
        assertThat(problem.get("properties").has("type")).isTrue();
        assertThat(problem.get("properties").has("title")).isTrue();
        assertThat(problem.get("properties").has("status")).isTrue();
        assertThat(problem.get("properties").has("detail")).isTrue();
        assertThat(problem.get("properties").has("instance")).isTrue();
        assertThat(problem.get("properties").has("code")).isTrue();
        assertThat(problem.get("properties").has("correlationId")).isTrue();
        assertThat(problem.get("properties").has("category")).isTrue();
        assertThat(problem.get("properties").has("retryable")).isTrue();
        assertThat(problem.get("required").toString()).contains("code", "correlationId", "category", "retryable");
        var paymentIntent = document.get("paths").get("/api/v1/receivables/{receivableId}/payment-intents").get("post");
        for (String status : new String[] {"400", "401", "403", "404", "409", "412", "429", "500", "502", "503", "504"}) {
            assertThat(paymentIntent.get("responses").has(status)).as("technical response %s", status).isTrue();
        }
        assertThat(paymentIntent.get("responses").has("428")).isFalse();
        var preconditioned = document.get("paths")
                .get("/api/v1/tenant-management/organization-registration-drafts/{registrationId}/steps/{step}")
                .get("put");
        assertThat(preconditioned.get("responses").has("428")).isTrue();
        assertThat(paymentIntent.get("responses").get("503").get("content").get("application/problem+json")
                .get("schema").get("$ref").asText()).isEqualTo("#/components/schemas/NexaProblemDetail");

        var operationIds = new HashSet<String>();
        document.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            var operationId = operation.getValue().get("operationId");
            if (operationId != null && operationId.isTextual()) {
                assertThat(operationIds.add(operationId.asText()))
                        .as("operationId must be unique: %s", operationId.asText())
                        .isTrue();
                assertThat(operationId.asText())
                        .as("operationId must be explicit instead of a generated suffix: %s", operationId.asText())
                        .doesNotMatch(".*_\\d+$");
            }
        }));

        var committed = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        assertThat(canonical(document)).as("runtime OpenAPI must equal committed canonical snapshot")
                .isEqualTo(canonical(committed));
    }

    private static String canonical(tools.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            return "{" + value.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> "\"" + entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value.isArray()) {
            var values = new java.util.ArrayList<String>();
            value.forEach(item -> values.add(canonical(item)));
            values.sort(java.util.Comparator.naturalOrder());
            return "[" + String.join(",", values) + "]";
        }
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static void assertRequiredHeader(tools.jackson.databind.JsonNode document, String path,
                                             String method, String name) {
        boolean required = false;
        for (tools.jackson.databind.JsonNode parameter : document.get("paths").get(path).get(method).get("parameters")) {
            if (name.equals(parameter.get("name").asText()) && "header".equals(parameter.get("in").asText())) {
                required = parameter.get("required").asBoolean();
            }
        }
        assertThat(required).as("%s %s must require header %s in OpenAPI", method, path, name).isTrue();
    }
}
