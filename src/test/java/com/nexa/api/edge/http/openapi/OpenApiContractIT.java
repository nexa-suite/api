package com.nexa.api.edge.http.openapi;

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
        Path snapshotPath = Path.of("docs/openapi/openapi.json");
        assertThat(document.get("openapi").asText()).isEqualTo("3.1.0");
        assertNullableMoneyProperty(document, "CatalogItemSummaryResponse", "basePrice");
        assertNullableMoneyProperty(document, "CatalogItemSummaryResponse", "currentOfferPrice");
        assertNullableMoneyProperty(document, "CatalogItemDetailResponse", "basePrice");
        assertNullableMoneyProperty(document, "CatalogItemDetailResponse", "currentOfferPrice");
        assertThat(document.get("paths").has("/api/v1/authentication/identity-sign-in")).isTrue();
        assertThat(document.get("paths").has("/api/v1/me/access-contexts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/me/access-context-selections")).isTrue();
        assertRequiredHeader(document, "/api/v1/authentication/identity-sign-in", "post", "X-Nexa-Client");
        assertHeaderRequired(document, "/api/v1/me/access-contexts", "get", "X-Nexa-Client", false);
        assertRequiredHeader(document, "/api/v1/me/access-contexts", "get", "X-Nexa-Surface");
        assertHeaderRequired(document, "/api/v1/me/access-context-selections", "post", "X-Nexa-Client", false);
        assertRequiredHeader(document, "/api/v1/me/access-context-selections", "post", "X-Nexa-Surface");
        assertHeaderRequired(document, "/api/v1/me/access-contexts", "get", "X-Nexa-Context-Ticket", false);
        assertHeaderRequired(document, "/api/v1/me/access-context-selections", "post", "X-Nexa-Context-Ticket", false);
        assertThat(document.at("/components/securitySchemes/contextTicket/type").asText()).isEqualTo("apiKey");
        assertThat(document.at("/components/securitySchemes/contextTicket/in").asText()).isEqualTo("header");
        assertThat(document.at("/components/securitySchemes/contextTicket/name").asText()).isEqualTo("X-Nexa-Context-Ticket");
        assertAuthorityAlternatives(document, "/api/v1/me/access-contexts", "get");
        assertAuthorityAlternatives(document, "/api/v1/me/access-context-selections", "post");
        assertThat(document.get("paths").get("/api/v1/me/access-context-selections").get("post")
                .get("responses").has("409")).isTrue();
        assertThat(document.at("/components/schemas/IdentitySignInResponse/properties").has("accessContextTicket")).isFalse();
        assertThat(document.at("/components/schemas/IdentitySignInResponse/properties/outcome/description").asText())
                .contains("SESSION_ESTABLISHED", "CONTEXT_SELECTION_REQUIRED", "NO_WORK_CONTEXT");
        assertThat(document.get("paths").get("/api/v1/authentication/identity-sign-in").get("post")
                .get("responses").get("200").get("headers").has("X-Nexa-Context-Ticket")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{warehouseId}/zones/{zoneId}")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/profile")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/location")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/hours")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/serviceability")).isTrue();
        assertThat(document.get("paths").has("/api/v1/warehouses/{id}/selection-policy")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/warehouses")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/route-starts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/dispatch-orders/{id}/handoff-notes")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/deliveries")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/deliveries/{deliveryId}")).isTrue();
        assertThat(document.get("paths").has("/api/v1/buyer/deliveries/{deliveryId}/events")).isTrue();
        assertThat(document.get("paths").has("/api/v1/my-deliveries/{id}/events")).isTrue();
        assertThat(document.get("paths").has("/api/v1/skus/resolve")).isTrue();
        assertThat(document.get("paths").has("/api/v1/inventory/lots/resolve")).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1dispatch-readiness/get/operationId").asText())
                .isEqualTo("listDispatchReadiness");
        assertThat(document.at("/paths/~1api~1v1~1dispatch-readiness~1{fulfillmentId}/get/operationId").asText())
                .isEqualTo("getDispatchReadiness");
        assertThat(document.get("paths").get("/api/v1/dispatch-readiness").get("get")
                .get("responses").get("200").get("content").get("*/*").get("schema").get("$ref").asText())
                .isEqualTo("#/components/schemas/DispatchReadinessPage");
        assertThat(document.get("paths").get("/api/v1/dispatch-readiness/{fulfillmentId}").get("get")
                .get("responses").get("200").get("content").get("*/*").get("schema").get("$ref").asText())
                .isEqualTo("#/components/schemas/DispatchReadiness");
        assertThat(document.get("paths").has("/api/v1/inventory/physical-allocation-scan-validations")).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1warehouses~1{warehouseId}~1access-grants/get/operationId").asText())
                .isEqualTo("listWarehouseAccessGrants");
        assertThat(document.at("/paths/~1api~1v1~1warehouses~1{warehouseId}~1access-grants/post/operationId").asText())
                .isEqualTo("grantWarehouseAccess");
        assertThat(document.at("/paths/~1api~1v1~1warehouses~1{warehouseId}~1access-grants~1{membershipId}/delete/operationId").asText())
                .isEqualTo("revokeWarehouseAccess");
        assertHeaderRequired(document, "/api/v1/warehouses/{warehouseId}/access-grants", "post", "If-Match", false);
        assertRequiredHeader(document, "/api/v1/warehouses/{warehouseId}/access-grants/{membershipId}", "delete", "If-Match");
        assertThat(document.at("/paths/~1api~1v1~1driver~1deliveries~1{deliveryId}~1instructions/get/operationId").asText())
                .isEqualTo("getCurrentDriverDeliveryInstructions");
        assertThat(document.at("/paths/~1api~1v1~1driver~1deliveries~1{deliveryId}~1instruction-acknowledgements/post/operationId").asText())
                .isEqualTo("acknowledgeCurrentDriverDeliveryInstructions");
        assertThat(document.at("/paths/~1api~1v1~1deliveries~1{deliveryId}~1instructions/post/operationId").asText())
                .isEqualTo("publishOperationalDeliveryInstruction");
        for (String path : java.util.List.of(
                "/api/v1/driver/workdays/current", "/api/v1/driver/workdays",
                "/api/v1/driver/workdays/{id}/locations", "/api/v1/driver/workdays/{id}/ends",
                "/api/v1/buyer/deliveries/{deliveryId}/live-location", "/api/v1/dispatch/drivers/{membershipId}/location",
                "/api/v1/dispatch/loads", "/api/v1/driver/loads", "/api/v1/driver/loads/{loadId}/acceptances",
                "/api/v1/sales-orders/{orderId}/customer-delivery-instructions",
                "/api/v1/buyer/sales-orders/{orderId}/customer-delivery-instructions",
                "/api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/resolutions",
                "/api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/closures")) {
            assertThat(document.get("paths").has(path)).as("current mobile contract: %s", path).isTrue();
        }
        assertThat(document.get("paths").has("/api/v1/deliveries/{deliveryId}/handoff-tokens")).isTrue();
        assertThat(document.get("paths").has("/api/v1/delivery-handoff/validations")).isTrue();
        assertThat(document.get("paths").has("/api/v1/deliveries/{deliveryId}/buyer-receipts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions/{subscriptionId}/disable")).isTrue();
        assertThat(document.get("paths").has("/api/v1/notifications/push-subscriptions/{subscriptionId}")).isTrue();
        assertSchemaRef(document, "/api/v1/public/contact-requests", "post", "requestBody",
                "#/components/schemas/Request");
        assertThat(document.at("/components/schemas/Request/properties").has("requestType")).isTrue();
        assertThat(document.at("/components/schemas/Request/properties").has("message")).isTrue();
        assertSchemaRef(document, "/api/v1/purchase-requests/field-submissions", "post", "requestBody",
                "#/components/schemas/FieldPurchaseRequestSubmissionRequest");
        assertThat(document.at("/components/schemas/FieldPurchaseRequestSubmissionRequest/properties/lines/items/$ref")
                .asText()).isEqualTo("#/components/schemas/FieldPurchaseRequestLine");
        assertThat(document.at("/components/schemas/FieldPurchaseRequestLine/properties").has("expectedUnitPrice")).isTrue();
        assertThat(document.at("/components/schemas/Line/properties/itemName").isMissingNode()).isFalse();
        assertSchemaRef(document, "/api/v1/dispatch-orders/{id}/assignments", "post", "requestBody",
                "#/components/schemas/AssignmentRequest");
        assertThat(document.at("/components/schemas/AssignmentRequest/properties/vehicleReference").isMissingNode())
                .isFalse();
        assertThat(document.at("/components/schemas/AssignmentRequest/properties/routeName").isMissingNode()).isFalse();
        assertSchemaRef(document, "/api/v1/fulfillments/{fulfillmentId}/driver-assignments", "post",
                "requestBody", "#/components/schemas/FulfillmentDriverAssignmentRequest");
        assertThat(document.at("/components/schemas/Check/properties/lines/items/$ref").asText())
                .isEqualTo("#/components/schemas/OutgoingGoodsCheckLine");
        assertResponseSchemaRef(document, "/api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings",
                "get", "200", "#/components/schemas/ExecutionTemperatureSnapshot");
        assertThat(document.at("/components/schemas/ExecutionTemperatureSnapshot/properties/lines/items/$ref")
                .asText())
                .isEqualTo("#/components/schemas/ExecutionTemperatureLine");
        assertThat(document.at("/components/schemas/ExecutionTemperatureSnapshot/properties/holds/items/$ref")
                .asText()).isEqualTo("#/components/schemas/ExecutionTemperatureHold");
        assertResponseSchemaRef(document, "/api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings",
                "post", "200", "#/components/schemas/ExecutionTemperatureReading");
        assertThat(document.at("/components/schemas/ExecutionTemperatureReading/properties/hold/$ref").asText())
                .isEqualTo("#/components/schemas/ExecutionTemperatureHold");
        assertThat(document.at("/components/schemas/Line/properties/itemName").isMissingNode()).isFalse();
        assertSchemaRef(document, "/api/v1/operational-exceptions/{exceptionId}/assignments", "post",
                "requestBody", "#/components/schemas/OperationalExceptionAssignmentRequest");
        assertSchemaRef(document, "/api/v1/operational-exceptions/{exceptionId}/resolutions", "post",
                "requestBody", "#/components/schemas/OperationalExceptionReasonRequest");
        assertSchemaRef(document, "/api/v1/inventory/lots/{lotId}/quarantines", "post", "requestBody",
                "#/components/schemas/ReasonRequest");
        assertThat(document.at("/components/schemas/ReasonRequest/required").isMissingNode()).isTrue();
        var issueResponse = document.get("paths").get("/api/v1/deliveries/{deliveryId}/handoff-tokens")
                .get("post").get("responses");
        var issueSchema = issueResponse.get("201").get("content").get("*/*").get("schema");
        assertThat(issueSchema.get("oneOf").toString()).contains("IssuedHandoffResponse",
                "IssuedDispatchHandoffResponse");
        assertThat(issueSchema.get("properties").has("attemptId")).isTrue();
        assertThat(issueSchema.get("properties").has("assignmentId")).isTrue();
        assertThat(issueSchema.get("properties").has("token")).isTrue();
        assertThat(issueSchema.has("required")).isFalse();
        assertThat(issueResponse.get("200").get("description").asText()).contains("token is omitted");
        var validationSchema = document.get("paths").get("/api/v1/delivery-handoff/validations")
                .get("post").get("responses").get("200").get("content").get("*/*").get("schema");
        assertThat(validationSchema.get("oneOf").toString()).contains("HandoffValidation",
                "DispatchHandoffValidationResponse");
        assertThat(validationSchema.get("properties").has("attemptId")).isTrue();
        assertThat(validationSchema.get("properties").has("assignmentId")).isTrue();
        assertThat(validationSchema.has("required")).isFalse();
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

        assertThat(document.get("paths").has("/api/v1/warehouses/{warehouseId}/inventory-availability")).isTrue();

        if (Boolean.getBoolean("nexa.openapi.write-snapshot")) {
            Files.writeString(snapshotPath, document.toString() + System.lineSeparator());
        }
        var committed = tools.jackson.databind.json.JsonMapper.shared().readTree(Files.readString(snapshotPath));
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

    private static void assertNullableMoneyProperty(tools.jackson.databind.JsonNode document, String schema,
                                                     String property) {
        var nullable = document.at("/components/schemas/" + schema + "/properties/" + property);
        assertThat(nullable.has("$ref")).as("nullable property must use an explicit union").isFalse();
        var alternatives = nullable.get("anyOf");
        assertThat(alternatives).as("nullable property %s.%s", schema, property).isNotNull();
        assertThat(alternatives).hasSize(2);
        assertThat(alternatives.toString()).contains("#/components/schemas/MoneyResponse");
        boolean includesNull = false;
        for (var alternative : alternatives) {
            var type = alternative.get("type");
            includesNull |= type != null && (type.isTextual() && "null".equals(type.asText())
                    || type.isArray() && type.toString().contains("\"null\""));
        }
        assertThat(includesNull).as("nullable property must include a JSON Schema null branch").isTrue();
    }

    private static void assertSchemaRef(tools.jackson.databind.JsonNode document, String path, String method,
                                        String body, String expected) {
        assertThat(document.get("paths").get(path).get(method).get(body).get("content")
                .get("application/json").get("schema").get("$ref").asText()).isEqualTo(expected);
    }

    private static void assertResponseSchemaRef(tools.jackson.databind.JsonNode document, String path, String method,
                                                String status, String expected) {
        assertThat(document.get("paths").get(path).get(method).get("responses").get(status)
                .get("content").get("*/*").get("schema").get("$ref").asText()).isEqualTo(expected);
    }

    private static void assertRequiredHeader(tools.jackson.databind.JsonNode document, String path,
                                             String method, String name) {
        assertHeaderRequired(document, path, method, name, true);
    }

    private static void assertHeaderRequired(tools.jackson.databind.JsonNode document, String path,
                                             String method, String name, boolean expectedRequired) {
        boolean required = false;
        for (tools.jackson.databind.JsonNode parameter : document.get("paths").get(path).get(method).get("parameters")) {
            if (name.equals(parameter.get("name").asText()) && "header".equals(parameter.get("in").asText())) {
                required = parameter.get("required").asBoolean();
            }
        }
        assertThat(required).as("%s %s header %s required=%s in OpenAPI", method, path, name, expectedRequired)
                .isEqualTo(expectedRequired);
    }

    private static void assertAuthorityAlternatives(tools.jackson.databind.JsonNode document, String path, String method) {
        var security = document.get("paths").get(path).get(method).get("security");
        assertThat(security).hasSize(2);
        assertThat(security.get(0).has("contextTicket") || security.get(1).has("contextTicket")).isTrue();
        assertThat(security.get(0).has("bearerAuth") || security.get(1).has("bearerAuth")).isTrue();
    }
}
