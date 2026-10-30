package com.nexa.api.edge.http.openapi;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OpenApiContractIT extends NexaWorkflowIntegrationSupport {
    private static final Set<String> CURRENT_VERSION_ETAG_OPERATIONS = Set.of(
            "DELETE /api/v1/client-accounts/{clientAccountId}/addresses/{addressId}#deactivateClientAccountAddress",
            "DELETE /api/v1/roles/{id}#deactivateRoleDefinition",
            "DELETE /api/v1/warehouses/{warehouseId}/access-grants/{membershipId}#revokeWarehouseAccess",
            "PATCH /api/v1/catalog/brands/{id}#updateBrand",
            "PATCH /api/v1/catalog/categories/{id}#updateCategory",
            "PATCH /api/v1/catalog/promotions/{id}#updateCatalogPromotion",
            "PATCH /api/v1/client-accounts/{clientAccountId}/addresses/{addressId}#updateClientAccountAddress",
            "PATCH /api/v1/custom-field-definitions/{id}#updateCustomField",
            "PATCH /api/v1/me/profile#updateProfile",
            "PATCH /api/v1/organization#updateOrganization",
            "PATCH /api/v1/roles/{id}#updateRoleDefinition",
            "PATCH /api/v1/settings/regional#updateRegional",
            "PATCH /api/v1/settings/security#updateSecurity",
            "PATCH /api/v1/settings/units#updateUnits",
            "PATCH /api/v1/warehouses/{id}#updateWarehouse",
            "PATCH /api/v1/warehouses/{id}/hours#updateHours",
            "PATCH /api/v1/warehouses/{id}/location#updateWarehouseLocation",
            "PATCH /api/v1/warehouses/{id}/locations#updateWarehouseLocationLegacyAlias",
            "PATCH /api/v1/warehouses/{id}/operational-profile#updateWarehouseOperationalProfileLegacyAlias",
            "PATCH /api/v1/warehouses/{id}/profile#updateWarehouseOperationalProfile",
            "PATCH /api/v1/warehouses/{id}/selection-policy#updateSelectionPolicy",
            "PATCH /api/v1/warehouses/{id}/serviceability#updateServiceability",
            "PATCH /api/v1/warehouses/{warehouseId}/zones/{zoneId}#updateZone",
            "PATCH /api/v1/workspace-memberships/{membershipId}/roles#roles",
            "PATCH /api/v1/workspaces/{workspaceId}#updateWorkspace",
            "PATCH /api/v1/workspaces/{workspaceId}/notifications#updateNotifications",
            "PATCH /api/v1/workspaces/{workspaceId}/operational-settings#updateOperational",
            "PATCH /api/v1/workspaces/{workspaceId}/settings#updateWorkspaceSettings",
            "POST /api/v1/buyer/sales-orders/{orderId}/customer-delivery-instructions#publishBuyerCustomerDeliveryInstruction",
            "POST /api/v1/catalog/brands/{id}/activations#activateBrand",
            "POST /api/v1/catalog/brands/{id}/deactivations#deactivateBrand",
            "POST /api/v1/catalog/categories/{id}/activations#activateCategory",
            "POST /api/v1/catalog/categories/{id}/deactivations#deactivateCategory",
            "POST /api/v1/catalog/promotions/{id}/activations#activateCatalogPromotion",
            "POST /api/v1/catalog/promotions/{id}/cancellations#cancelCatalogPromotion",
            "POST /api/v1/catalog/promotions/{id}/expirations#expireCatalogPromotion",
            "POST /api/v1/catalog/promotions/{id}/pauses#pauseCatalogPromotion",
            "POST /api/v1/catalog/promotions/{id}/resumptions#resumeCatalogPromotion",
            "POST /api/v1/catalog/promotions/{id}/schedules#scheduleCatalogPromotion",
            "POST /api/v1/custom-field-definitions/{id}/activations#activateCustomField",
            "POST /api/v1/custom-field-definitions/{id}/deactivations#deactivateCustomField",
            "POST /api/v1/deliveries/{deliveryId}/attempts#recordDeliveryAttempt",
            "POST /api/v1/deliveries/{deliveryId}/execution-holds/{holdId}/dispositions#dispose",
            "POST /api/v1/deliveries/{deliveryId}/instructions#publishOperationalDeliveryInstruction",
            "POST /api/v1/deliveries/{deliveryId}/pod/seals#sealDeliveryProofOfDelivery",
            "POST /api/v1/deliveries/{deliveryId}/transit-starts#startDeliveryTransit",
            "POST /api/v1/dispatch-orders/{id}/assignments#assign",
            "POST /api/v1/dispatch-orders/{id}/cancellations#cancelDispatchOrder",
            "POST /api/v1/dispatch-orders/{id}/delivery-attempts#recordFailedDeliveryAttempt",
            "POST /api/v1/dispatch-orders/{id}/delivery-completions#complete",
            "POST /api/v1/dispatch-orders/{id}/delivery-partials#completePartialDeliveryLegacyAlias",
            "POST /api/v1/dispatch-orders/{id}/failed-delivery-attempts#recordFailedDeliveryAttemptLegacyAlias",
            "POST /api/v1/dispatch-orders/{id}/incidents#incident",
            "POST /api/v1/dispatch-orders/{id}/partial-deliveries#completePartialDelivery",
            "POST /api/v1/dispatch-orders/{id}/preparation-starts#prepare",
            "POST /api/v1/dispatch-orders/{id}/reprogrammings#reprogram",
            "POST /api/v1/dispatch-orders/{id}/route-readiness#ready",
            "POST /api/v1/dispatch-orders/{id}/route-starts#routeStart",
            "POST /api/v1/dispatch-orders/{id}/schedules#schedule",
            "POST /api/v1/dispatch-orders/{id}/temperature-readings#temperature",
            "POST /api/v1/dispatch/loads/{loadId}/assignments#assignDispatchDeliveryLoadDriver",
            "POST /api/v1/dispatch/loads/{loadId}/handoff-confirmations#confirmDispatchDeliveryLoadHandoff",
            "POST /api/v1/dispatch/loads/{loadId}/offers#offerDispatchDeliveryLoadToDriver",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts#startCurrentDriverDeliveryAttempt",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/arrivals#signalCurrentDriverArrival",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents#recordCurrentDriverDeliveryIncident",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents/{incidentId}/evidence#attachCurrentDriverDeliveryIncidentEvidence",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/outcomes#recordCurrentDriverAttemptOutcome",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/proof-of-delivery#createCurrentDriverProofOfDelivery",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/proof-of-delivery/{podId}/evidence#attachCurrentDriverProofEvidence",
            "POST /api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings#record",
            "POST /api/v1/driver/deliveries/{deliveryId}/instruction-acknowledgements#acknowledgeCurrentDriverDeliveryInstructions",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/claims#claimCurrentDriverDeliveryOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/closures#closeCurrentDriverWarningOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/resolutions#resolveCurrentDriverWarningOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/reviews#startCurrentDriverDeliveryOperationalExceptionReview",
            "POST /api/v1/driver/loads/{loadId}/acceptances#acceptAssignedDriverDeliveryLoad",
            "POST /api/v1/driver/workdays/{id}/ends#endDriverWorkday",
            "POST /api/v1/driver/workdays/{id}/location-availability#recordDriverLocationAvailability",
            "POST /api/v1/fulfillment-candidates/{salesOrderId}/inventory-reservations#reserve",
            "POST /api/v1/fulfillments/{fulfillmentId}/dispatch-window-plans#planMissingFulfillmentDispatchWindow",
            "POST /api/v1/fulfillments/{fulfillmentId}/dispatches#dispatchFulfillment",
            "POST /api/v1/fulfillments/{fulfillmentId}/driver-assignments#assignDriverToPreparedFulfillment",
            "POST /api/v1/fulfillments/{fulfillmentId}/driver-assignments/plan-changes#changePreparedFulfillmentDispatchPlan",
            "POST /api/v1/fulfillments/{fulfillmentId}/outgoing-checks#recordOutgoingGoodsCheck",
            "POST /api/v1/fulfillments/{fulfillmentId}/outgoing-discrepancy-resolutions#resolveOutgoingGoodsDiscrepancy",
            "POST /api/v1/fulfillments/{fulfillmentId}/packing#packFulfillment",
            "POST /api/v1/fulfillments/{fulfillmentId}/picking-confirmations#confirmFulfillmentPicking",
            "POST /api/v1/fulfillments/{fulfillmentId}/picking-starts#startFulfillmentPicking",
            "POST /api/v1/fulfillments/{fulfillmentId}/ready-for-dispatch#markFulfillmentReadyForDispatch",
            "POST /api/v1/fulfillments/{fulfillmentId}/shortage-resolutions#resolveFulfillmentShortage",
            "POST /api/v1/fulfillments/{fulfillmentId}/staging#stageFulfillment",
            "POST /api/v1/inventory-reservations/{id}/releases#release",
            "POST /api/v1/inventory/adjustments#adjust",
            "POST /api/v1/inventory/cycle-counts/{countId}/corrections#applyInventoryCycleCountCorrection",
            "POST /api/v1/inventory/inbound-discrepancy-cases/{caseId}/submissions#submitInboundReceivingDiscrepancyForReview",
            "POST /api/v1/inventory/inbound-discrepancy-cases#recordInboundReceivingDiscrepancy",
            "POST /api/v1/inventory/lots/{lotId}/availability-restorations#restoreLot",
            "POST /api/v1/inventory/lots/{lotId}/blocks#blockLot",
            "POST /api/v1/inventory/lots/{lotId}/dispositions#recordInventoryLotDisposition",
            "POST /api/v1/inventory/lots/{lotId}/quarantines#quarantineLot",
            "POST /api/v1/inventory/physical-allocation-substitution-requests#requestPhysicalAllocationLotSubstitution",
            "POST /api/v1/inventory/transfers/{id}/dispatches#dispatchInventoryTransfer",
            "POST /api/v1/inventory/transfers/{id}/receipts#receiveInventoryTransfer",
            "POST /api/v1/inventory/waste-movements#waste",
            "POST /api/v1/operational-exceptions/{exceptionId}/assignments#reassignOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/claims#claimOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/closures#closeWarningOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/follow-ups#appendOperationalExceptionFollowUp",
            "POST /api/v1/operational-exceptions/{exceptionId}/resolutions#resolveWarningOperationalExceptionCoordination",
            "POST /api/v1/organization-invitations/{invitationId}/resends#resendOrganizationInvitation",
            "POST /api/v1/organization-invitations/{invitationId}/revocations#revokeOrganizationInvitation",
            "POST /api/v1/product-families/{familyId}/activations#activateProductFamily",
            "POST /api/v1/product-families/{familyId}/deactivations#deactivateProductFamily",
            "POST /api/v1/purchase-requests/{id}/adjustment-requests#requestPurchaseRequestAdjustment",
            "POST /api/v1/purchase-requests/{id}/approvals#approvePurchaseRequest",
            "POST /api/v1/purchase-requests/{id}/cancellations#cancelPurchaseRequest",
            "POST /api/v1/purchase-requests/{id}/material-changes#proposePurchaseRequestMaterialChange",
            "POST /api/v1/purchase-requests/{id}/material-changes/{proposalId}/acceptances#acceptPurchaseRequestMaterialChange",
            "POST /api/v1/purchase-requests/{id}/rejections#rejectPurchaseRequest",
            "POST /api/v1/purchase-requests/{id}/reviews#startPurchaseRequestReview",
            "POST /api/v1/purchase-requests/{id}/withdrawals#withdrawPurchaseRequest",
            "POST /api/v1/purchase-requests/{purchaseRequestId}/order-conversions#convert",
            "POST /api/v1/receivables/{receivableId}/financial-adjustments#postPostPaymentFinancialAdjustment",
            "POST /api/v1/sales-orders/manual-drafts/{draftId}/abandonments#abandonManualSalesOrderDraft",
            "POST /api/v1/sales-orders/{id}/cancellations#cancel",
            "POST /api/v1/sales-orders/{id}/confirmations#confirm",
            "POST /api/v1/sales-orders/{id}/rejections#reject",
            "POST /api/v1/sales-orders/{orderId}/customer-delivery-instructions#recordSalesReceivedCustomerDeliveryInstruction",
            "POST /api/v1/skus/{skuId}/activations#activateSellableSku",
            "POST /api/v1/skus/{skuId}/deactivations#deactivateSellableSku",
            "POST /api/v1/tenant-management/organization-registration-drafts/{registrationId}/submit#submitOrganizationRegistrationDraft",
            "POST /api/v1/warehouses/{warehouseId}/access-grants#grantWarehouseAccess",
            "POST /api/v1/workspace-memberships/{membershipId}/reactivations#reactivate",
            "POST /api/v1/workspace-memberships/{membershipId}/revocations#revokeWorkspaceMembership",
            "POST /api/v1/workspace-memberships/{membershipId}/suspensions#suspend",
            "POST /api/v1/workspaces/{workspaceId}/reactivations#reactivateWorkspace",
            "POST /api/v1/workspaces/{workspaceId}/suspensions#suspendWorkspace",
            "PUT /api/v1/client-accounts/{clientAccountId}/addresses/{addressId}/default#setDefaultClientAccountAddress",
            "PUT /api/v1/client-accounts/{id}/sales-owner#assignClientAccountSalesOwner",
            "PUT /api/v1/dispatch/loads/{loadId}/stops#reorderDispatchDeliveryLoadStops",
            "PUT /api/v1/inventory/safety-stocks#upsertInventorySafetyStock",
            "PUT /api/v1/sales-orders/manual-drafts/{draftId}/client#updateManualSalesOrderDraftClient",
            "PUT /api/v1/sales-orders/manual-drafts/{draftId}/delivery#updateManualSalesOrderDraftDelivery",
            "PUT /api/v1/sales-orders/manual-drafts/{draftId}/items#replaceManualSalesOrderDraftItems",
            "PUT /api/v1/tenant-management/organization-registration-drafts/{registrationId}/steps/{step}#updateOrganizationRegistrationDraftStep");

    private static final Set<String> REPLAYABLE_CREATED_OPERATIONS = Set.of(
            "POST /api/v1/deliveries/{deliveryId}/buyer-receipts#recordBuyerDeliveryReceipt",
            "POST /api/v1/delivery-handoff/receipts#acceptBuyerDeliveryHandoff",
            "POST /api/v1/deliveries/{deliveryId}/instructions#publishOperationalDeliveryInstruction",
            "POST /api/v1/deliveries/{deliveryId}/execution-holds/{holdId}/dispositions#dispose",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts#startCurrentDriverDeliveryAttempt",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/arrivals#signalCurrentDriverArrival",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents#recordCurrentDriverDeliveryIncident",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents/{incidentId}/evidence#attachCurrentDriverDeliveryIncidentEvidence",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/proof-of-delivery#createCurrentDriverProofOfDelivery",
            "POST /api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/proof-of-delivery/{podId}/evidence#attachCurrentDriverProofEvidence",
            "POST /api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings#record",
            "POST /api/v1/driver/deliveries/{deliveryId}/instruction-acknowledgements#acknowledgeCurrentDriverDeliveryInstructions",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/claims#claimCurrentDriverDeliveryOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/closures#closeCurrentDriverWarningOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/resolutions#resolveCurrentDriverWarningOperationalException",
            "POST /api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/reviews#startCurrentDriverDeliveryOperationalExceptionReview",
            "POST /api/v1/fulfillments/{fulfillmentId}/dispatch-window-plans#planMissingFulfillmentDispatchWindow",
            "POST /api/v1/fulfillments/{fulfillmentId}/outgoing-checks#recordOutgoingGoodsCheck",
            "POST /api/v1/fulfillments/{fulfillmentId}/outgoing-discrepancy-resolutions#resolveOutgoingGoodsDiscrepancy",
            "POST /api/v1/inventory/inbound-discrepancy-cases#recordInboundReceivingDiscrepancy",
            "POST /api/v1/inventory/inbound-discrepancy-cases/{caseId}/submissions#submitInboundReceivingDiscrepancyForReview",
            "POST /api/v1/inventory/physical-allocation-substitution-requests#requestPhysicalAllocationLotSubstitution",
            "POST /api/v1/operational-exceptions/{exceptionId}/assignments#reassignOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/claims#claimOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/closures#closeWarningOperationalExceptionCoordination",
            "POST /api/v1/operational-exceptions/{exceptionId}/follow-ups#appendOperationalExceptionFollowUp",
            "POST /api/v1/operational-exceptions/{exceptionId}/resolutions#resolveWarningOperationalExceptionCoordination");

    @Test void runtimeOpenApiContainsWarehouseAndLogisticsContracts() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = json(result);
        Path snapshotPath = Path.of("docs/openapi/openapi.json");
        assertThat(document.get("openapi").asText()).isEqualTo("3.1.0");
        assertResponseSchemaRef(document, "/api/v1/client-accounts/me", "get", "200",
                "#/components/schemas/BuyerClientAccountSelfResponse");
        assertResponseSchemaRef(document, "/api/v1/client-accounts/me", "post", "201",
                "#/components/schemas/BuyerClientAccountRegistrationResponse");
        assertResponseSchemaRef(document, "/api/v1/client-accounts/me", "post", "200",
                "#/components/schemas/BuyerClientAccountRegistrationResponse");
        for (String property : Set.of("contactPerson", "contactEmail", "phone")) {
            assertNullableStringProperty(document, "BuyerClientAccountRegistrationResponse", property);
        }
        var buyerAccountProperties = document.at("/components/schemas/BuyerClientAccountSelfResponse/properties");
        for (String property : Set.of("contactPerson", "contactEmail", "phone", "buyerMembershipId")) {
            assertThat(buyerAccountProperties.has(property))
                    .as("Buyer self projection excludes shared contact and membership identity %s", property)
                    .isFalse();
        }
        assertCurrentVersionEtags(document);
        assertReplayableCreatedResponses(document);
        assertOperationSuccessStatuses(document,
                "POST /api/v1/fulfillment-candidates/{salesOrderId}/inventory-reservations#reserve", Set.of("201"));
        assertThat(findOperationByContractKey(document,
                "POST /api/v1/fulfillment-candidates/{salesOrderId}/inventory-reservations#reserve")
                .path("responses").has("409"))
                .as("reserve retains its actual conflict outcome").isTrue();
        assertNullableMoneyProperty(document, "CatalogItemSummaryResponse", "basePrice");
        assertNullableMoneyProperty(document, "CatalogItemSummaryResponse", "currentOfferPrice");
        assertNullableMoneyProperty(document, "CatalogItemDetailResponse", "basePrice");
        assertNullableMoneyProperty(document, "CatalogItemDetailResponse", "currentOfferPrice");
        assertThat(document.get("paths").has("/api/v1/authentication/identity-sign-in")).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1direct-orders~1delivery-policy/get/operationId").asText())
                .isEqualTo("getDirectOrderDeliveryPolicy");
        assertThat(document.at("/paths/~1api~1v1~1direct-orders/get").isMissingNode())
                .as("delivery policy is not published as a GET on the collection path")
                .isTrue();
        assertResponseSchemaRef(document, "/api/v1/direct-orders/delivery-policy", "get", "200",
                "#/components/schemas/DirectOrderDeliveryPolicy");
        assertSecurityRequirement(document, "getDirectOrderDeliveryPolicy", Set.of("bearerAuth"));
        assertThat(document.at("/components/schemas/DirectOrderDeliveryPolicy/required").toString())
                .contains("minimumDeliveryLeadDays", "earliestAllowedDeliveryDate");
        assertThat(document.at("/components/schemas/CatalogQueryParameters/properties/brandId").isMissingNode())
                .as("Buyer catalog filter exposes its stable brand identifier")
                .isFalse();
        assertQueryParameterDescription(document, "/api/v1/catalog/products", "get", "search",
                "Partial, case-insensitive match against product name, code, legacy catalog item ID, or slug.");
        assertQueryParameterDescription(document, "/api/v1/catalog/products", "get", "brand",
                "Exact, case-insensitive brand name.");
        assertQueryParameterDescription(document, "/api/v1/catalog/products", "get", "category",
                "Exact, case-insensitive category name.");
        assertQueryParameterDescription(document, "/api/v1/product-families", "get", "search",
                "Partial, case-insensitive match against family name or family code.");
        assertQueryParameterDescription(document, "/api/v1/product-families", "get", "brand",
                "Exact, case-insensitive brand name.");
        assertQueryParameterDescription(document, "/api/v1/product-families", "get", "category",
                "Exact, case-insensitive category name.");
        assertThat(document.at("/components/schemas/CatalogQueryParameters/properties/categoryId").isMissingNode())
                .as("Buyer catalog filter exposes its stable category identifier")
                .isFalse();
        assertThat(document.get("paths").has("/api/v1/authentication/csrf")).isTrue();
        assertThat(document.get("paths").get("/api/v1/authentication/csrf").has("get")).isTrue();
        assertThat(document.get("paths").get("/api/v1/authentication/csrf").get("get").has("parameters")).isFalse();
        assertThat(document.get("paths").has("/api/v1/me/access-contexts")).isTrue();
        assertThat(document.get("paths").has("/api/v1/me/access-context-selections")).isTrue();
        for (String operationId : Set.of("driverRead", "record", "dispose", "listCustomerFieldVisitEvidence",
                "recordCustomerFieldVisitEvidence", "dispositionRead", "listVisibleCatalogFilterOptions")) {
            assertSecurityRequirement(document, operationId, Set.of("bearerAuth"));
        }
        for (String operationId : Set.of("activate", "rejectOrganizationRegistration")) {
            assertSecurityRequirement(document, operationId, Set.of("systemOperator"));
        }
        for (String operationId : Set.of("listInternalSupportRequests", "requestTemporarySalesOrderSupport",
                "revokeTemporarySalesOrderSupportByOperator", "approveTemporarySalesOrderSupport",
                "readSalesOrderUnderSupportGrant", "listInternalOnboardingHealth")) {
            assertSecurityRequirement(document, operationId, Set.of("internalOperatorId", "internalOperatorToken"));
        }
        assertApiKeySecurityScheme(document, "systemOperator", "X-Nexa-System-Operator");
        assertApiKeySecurityScheme(document, "internalOperatorId", "X-Nexa-Internal-Operator-Id");
        assertApiKeySecurityScheme(document, "internalOperatorToken", "X-Nexa-Internal-Operator-Token");
        assertOptionalBearerForSignOut(document);
        assertResponseSchemaRef(document, "/api/v1/buyer/purchase-request-drafts", "get", "200",
                "#/components/schemas/BuyerPurchaseRequestDraftPage");
        assertResponseSchemaRef(document, "/api/v1/buyer/purchase-request-drafts", "post", "201",
                "#/components/schemas/BuyerPurchaseRequestDraftView");
        assertResponseSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}", "get", "200",
                "#/components/schemas/BuyerPurchaseRequestDraftView");
        assertResponseSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/review", "get", "200",
                "#/components/schemas/BuyerPurchaseRequestReviewView");
        assertSchemaRef(document, "/api/v1/buyer/purchase-request-drafts", "post", "requestBody",
                "#/components/schemas/BuyerPurchaseRequestDraftCreateRequest");
        assertSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/lines", "put", "requestBody",
                "#/components/schemas/BuyerPurchaseRequestDraftLinesRequest");
        assertSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/destination", "put", "requestBody",
                "#/components/schemas/BuyerPurchaseRequestDraftDestinationRequest");
        assertSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/route-previews", "post", "requestBody",
                "#/components/schemas/BuyerPurchaseRequestDraftRouteRequest");
        assertSchemaRef(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/preferences", "put", "requestBody",
                "#/components/schemas/BuyerPurchaseRequestDraftPreferencesRequest");
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftView/properties").has("clientAccountId")).isTrue();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftView/properties").has("destination")).isTrue();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftView/properties").has("warehouseSelection")).isTrue();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftView/properties").has("readyToCreate")).isFalse();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestReviewView/properties").has("productsComplete")).isTrue();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestReviewView/properties").has("readyToSubmit")).isTrue();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestReviewView/properties").has("clientComplete")).isFalse();
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftLineRequest/required").toString())
                .contains("skuId", "quantity");
        assertThat(document.at("/components/schemas/BuyerPurchaseRequestDraftLineRequest/properties").has("catalogItemId"))
                .isFalse();
        assertThat(document.at("/components/schemas/AccessMatrixEntry/properties/permissionKeys/description").asText())
                .contains("Canonical PermissionKey codes");
        assertThat(document.at("/components/schemas/AccessMatrixEntry/properties/permissions/description").asText())
                .contains("Legacy permission codes");
        assertThat(document.at("/paths/~1api~1v1~1access-matrix/get/description").asText())
                .contains("built-in role template catalogs only");
        assertThat(document.get("paths").has("/api/v1/buyer/deliveries/{deliveryId}/buyer-receipts")).isTrue();
        assertThat(document.get("paths").get("/api/v1/buyer/deliveries/{deliveryId}/buyer-receipts").has("get")).isTrue();
        assertThat(document.get("paths").has("/api/v1/catalog-items/filter-options")).isTrue();
        assertThat(document.get("paths").get("/api/v1/catalog-items/filter-options").has("get")).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1tenant-management~1organization-registration-drafts/post/responses").has("201")).isTrue();
        assertRequiredHeader(document,
                "/api/v1/tenant-management/organization-registration-drafts/{registrationId}", "get", "X-Resume-Token");
        assertRequiredHeader(document,
                "/api/v1/tenant-management/organization-registration-drafts/{registrationId}/steps/{step}", "put", "X-Resume-Token");
        assertRequiredHeader(document,
                "/api/v1/tenant-management/organization-registration-drafts/{registrationId}/submit", "post", "X-Resume-Token");
        assertHeaderDescription(document,
                "/api/v1/tenant-management/organization-registration-drafts/{registrationId}", "get", "X-Resume-Token",
                "Read the draft using its resume token; accepted compatibility alias: X-Organization-Registration-Token.");
        assertThat(document.at("/paths/~1api~1v1~1skus~1{skuId}~1prices/post/responses").has("201")).isTrue();
        for (String operationId : java.util.List.of("createReceivable", "createReceivablePaymentIntent",
                "createCreditLinePayment", "createBankTransferPayment", "createBuyerWalletRecharge",
                "requestBusinessDocumentEvidence", "uploadBusinessDocumentEvidence", "submitFieldPurchaseRequest",
                "createPurchaseRequestDraft", "createManualSalesOrderDraft", "submitManualSalesOrderDraft",
                "createManualSalesOrder", "recordInventoryCycleCount", "createInventoryTransfer",
                "recordInventoryTransferReceiptObservation", "createOrganizationInvitation",
                "acceptOrganizationInvitation", "createRoleDefinition", "requestTemporarySalesOrderSupport",
                "createDispatchDeliveryLoad", "startFulfillment", "captureDeliveryProofOfDelivery",
                "recordDeliveryTemperatureEvidence", "recordStockTemperatureEvidence",
                "recordFulfillmentTemperatureEvidence", "createDispatchOrder", "createCatalogPromotion",
                "createProductFamily", "createProductVariant", "createSellableSku", "registerNativePushSubscription",
                "createClientAccountAddress", "recordCustomerFieldVisitEvidence", "createWarehouse", "createZone",
                "receive", "createCustomField", "createWorkspace", "appendHandoffNote", "createCategory", "createBrand")) {
            assertOperationResponse(document, operationId, "201", false);
        }
        for (String operationId : java.util.List.of("submitPublicContactRequest", "requestBusinessDocumentGeneration",
                "regenerateBusinessDocument", "replaceBusinessDocument", "receiveStripeWebhook")) {
            assertOperationResponse(document, operationId, "202", false);
        }
        for (String operationId : java.util.List.of("createReceivable", "createReceivablePaymentIntent",
                "createCreditLinePayment", "createBankTransferPayment", "createBuyerWalletRecharge",
                "requestBusinessDocumentEvidence", "uploadBusinessDocumentEvidence", "createPurchaseRequestDraft",
                "createManualSalesOrderDraft", "submitManualSalesOrderDraft", "createManualSalesOrder",
                "createProductFamily", "createProductVariant", "createSellableSku")) {
            assertOperationResponseHeader(document, operationId, "201", "Location");
        }
        for (String operationId : java.util.List.of("requestBusinessDocumentGeneration", "regenerateBusinessDocument",
                "replaceBusinessDocument")) {
            assertOperationResponseHeader(document, operationId, "202", "Location");
        }
        for (String operationId : java.util.List.of("createPurchaseRequestDraft", "createManualSalesOrderDraft",
                "submitManualSalesOrderDraft", "createManualSalesOrder", "createProductFamily", "createSellableSku",
                "submitFieldPurchaseRequest", "createClientAccountAddress", "createCategory", "createBrand",
                "createRoleDefinition", "createWorkspace", "createWarehouse", "createZone", "receive",
                "recordInventoryCycleCount", "createInventoryTransfer", "recordInventoryTransferReceiptObservation",
                "createOrganizationInvitation", "createCustomField", "createDispatchDeliveryLoad", "appendHandoffNote",
                "createDispatchOrder", "startFulfillment", "captureDeliveryProofOfDelivery",
                "recordDeliveryTemperatureEvidence", "recordFulfillmentTemperatureEvidence", "createCatalogPromotion")) {
            assertOperationResponseHeader(document, operationId, "201", "ETag");
        }
        assertNoContentResponse(document, "/api/v1/notifications/{id}/read", "post");
        assertNoContentResponse(document, "/api/v1/notifications/{id}/read", "delete");
        assertNoContentResponse(document, "/api/v1/notifications/read-all", "post");
        assertNoContentResponse(document, "/api/v1/business-document-evidence/{evidenceId}", "delete");
        assertNoContentResponse(document, "/api/v1/notifications/push-subscriptions/{subscriptionId}", "delete");
        for (String[] route : java.util.List.of(
                new String[] {"/api/v1/fulfillments/{fulfillmentId}/driver-assignments", "get"},
                new String[] {"/api/v1/driver/workdays/current", "get"},
                new String[] {"/api/v1/deliveries/{deliveryId}/pod", "get"},
                new String[] {"/api/v1/fulfillments/{fulfillmentId}/outgoing-checks/current", "get"},
                new String[] {"/api/v1/fulfillments/{fulfillmentId}/outgoing-checks/current-summary", "get"})) {
            assertConditionalNoContentResponse(document, route[0], route[1]);
        }
        assertIfMatchRequiredWhenPreconditioned(document);
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/submissions", "post", "If-Match");
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/submissions", "post", "Idempotency-Key");
        for (String operationId : Set.of(
                "updateOrganizationRegistrationDraftStep", "submitOrganizationRegistrationDraft", "createWorkspace",
                "createOrganizationInvitation", "configureClientCreditAccount", "createProductVariant", "createSkuPrice",
                "createCategory", "createBrand", "createCatalogPromotion", "createDirectOrder",
                "proposePurchaseRequestMaterialChange", "acceptPurchaseRequestMaterialChange",
                "rejectPurchaseRequestMaterialChange", "upsertInventorySafetyStock", "waste", "createInventoryTransfer",
                "receiveInventoryTransfer", "recordInventoryTransferReceiptObservation", "dispatchInventoryTransfer",
                "requestPhysicalAllocationLotSubstitution", "quarantineLot", "recordInventoryLotDisposition",
                "recordInventoryCycleCount", "blockLot", "restoreLot", "receive", "recordInboundReceivingDiscrepancy",
                "submitInboundReceivingDiscrepancyForReview", "applyInventoryCycleCountCorrection", "adjust",
                "createDispatchOrder", "release", "reserve", "recordStockTemperatureEvidence", "startFulfillment",
                "recordFulfillmentTemperatureEvidence", "stageFulfillment", "resolveFulfillmentShortage",
                "markFulfillmentReadyForDispatch", "startFulfillmentPicking", "confirmFulfillmentPicking",
                "packFulfillment", "resolveOutgoingGoodsDiscrepancy", "recordOutgoingGoodsCheck",
                "assignDriverToPreparedFulfillment", "changePreparedFulfillmentDispatchPlan", "dispatchFulfillment",
                "planMissingFulfillmentDispatchWindow", "startDeliveryTransit", "recordDeliveryTemperatureEvidence",
                "captureDeliveryProofOfDelivery", "sealDeliveryProofOfDelivery", "recordDeliveryAttempt",
                "acceptAssignedDriverDeliveryLoad", "claimCurrentDriverDeliveryOperationalException",
                "startCurrentDriverDeliveryOperationalExceptionReview", "resolveCurrentDriverWarningOperationalException",
                "closeCurrentDriverWarningOperationalException", "acknowledgeCurrentDriverDeliveryInstructions",
                "startCurrentDriverDeliveryAttempt", "createCurrentDriverProofOfDelivery", "attachCurrentDriverProofEvidence",
                "recordCurrentDriverAttemptOutcome", "recordCurrentDriverDeliveryIncident",
                "attachCurrentDriverDeliveryIncidentEvidence", "signalCurrentDriverArrival",
                "claimOperationalExceptionCoordination", "reassignOperationalExceptionCoordination",
                "appendOperationalExceptionFollowUp", "resolveWarningOperationalExceptionCoordination",
                "closeWarningOperationalExceptionCoordination", "createDispatchDeliveryLoad",
                "reorderDispatchDeliveryLoadStops", "assignDispatchDeliveryLoadDriver", "offerDispatchDeliveryLoadToDriver",
                "confirmDispatchDeliveryLoadHandoff", "temperature", "schedule", "routeStart", "ready", "reprogram",
                "prepare", "completePartialDelivery", "incident", "appendHandoffNote", "recordFailedDeliveryAttemptLegacyAlias",
                "completePartialDeliveryLegacyAlias", "complete", "recordFailedDeliveryAttempt", "cancelDispatchOrder",
                "assign", "publishOperationalDeliveryInstruction")) {
            assertOperationHeaderRequired(document, operationId, "Idempotency-Key", true);
        }
        for (String operationId : Set.of("withdrawPurchaseRequest", "startPurchaseRequestReview",
                "rejectPurchaseRequest", "cancelPurchaseRequest", "approvePurchaseRequest",
                "requestPurchaseRequestAdjustment", "confirm", "reject", "cancel")) {
            assertOperationHeaderRequired(document, operationId, "Idempotency-Key", false);
        }
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}", "patch", "If-Match");
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/lines", "post", "If-Match");
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/lines/{lineId}", "patch", "If-Match");
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/lines/{lineId}", "delete", "If-Match");
        for (String action : java.util.List.of("reviews", "adjustment-requests", "approvals", "rejections",
                "cancellations", "withdrawals", "material-changes")) {
            assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/" + action, "post", "If-Match");
        }
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/material-changes/{proposalId}/acceptances", "post", "If-Match");
        assertRequiredHeader(document, "/api/v1/purchase-requests/{id}/material-changes/{proposalId}/rejections", "post", "If-Match");
        assertRequiredHeader(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/submissions", "post", "If-Match");
        assertRequiredHeader(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/submissions", "post", "Idempotency-Key");
        for (String action : java.util.List.of("lines", "destination", "route-previews", "preferences")) {
            String method = "route-previews".equals(action) ? "post" : "put";
            assertRequiredHeader(document, "/api/v1/buyer/purchase-request-drafts/{draftId}/" + action, method, "If-Match");
        }
        for (String operationId : java.util.List.of("getPurchaseRequestDraft", "replacePurchaseRequestDraftLines", "setPurchaseRequestDraftDestination",
                "previewPurchaseRequestDraftRoute", "setPurchaseRequestDraftPreferences", "submitPurchaseRequestDraft")) {
            assertOperationResponseHeader(document, operationId, "200", "ETag");
        }
        assertRequiredHeader(document, "/api/v1/me/profile", "patch", "If-Match");
        assertNoContentResponse(document, "/api/v1/me/password-changes", "post");
        assertNoContentResponse(document, "/api/v1/me/sessions/{sessionId}", "delete");
        assertNoContentResponse(document, "/api/v1/me/session-revocations", "post");
        assertNoContentResponse(document, "/api/v1/auth/password-resets", "post");
        assertDeprecated(document, "/api/v1/warehouses/{id}/locations", "get");
        assertDeprecated(document, "/api/v1/warehouses/{id}/operational-profile", "get");
        assertDeprecated(document, "/api/v1/warehouses/{id}/operational-profile", "patch");
        assertDeprecated(document, "/api/v1/warehouses/{id}/locations", "patch");
        assertDeprecated(document, "/api/v1/dispatch-orders/{id}/failed-delivery-attempts", "post");
        assertDeprecated(document, "/api/v1/dispatch-orders/{id}/delivery-partials", "post");
        assertDeprecated(document, "/api/v1/plan-usage", "get");
        assertDeprecated(document, "/api/v1/plan-comparison", "get");
        assertDeprecated(document, "/api/v1/tenant-management/organization-registrations", "post");
        assertDeprecated(document, "/api/v1/tenant-management/organization-registrations/{registrationId}", "get");
        assertDeprecated(document, "/api/v1/catalog/products/{productId}/prices", "get");
        assertDeprecated(document, "/api/v1/purchase-requests/{id}/cancellations", "post");
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
        assertThat(document.get("paths").has("/api/v1/buyer/delivery-lead-policy")).isTrue();
        assertThat(document.get("paths").get("/api/v1/buyer/delivery-lead-policy").get("get")
                .get("operationId").asText()).isEqualTo("getBuyerDeliveryLeadPolicy");
        var leadPolicyProperties = document.at(
                "/components/schemas/BuyerDeliveryLeadPolicyResponse/properties");
        assertThat(leadPolicyProperties.has("minimumDeliveryLeadBusinessDays")).isTrue();
        assertThat(leadPolicyProperties.has("minimumAllowedDeliveryDate")).isTrue();
        assertThat(leadPolicyProperties.has("timezone")).isTrue();
        assertThat(leadPolicyProperties.size()).isEqualTo(3);
        assertThat(document.get("paths").get("/api/v1/buyer/delivery-lead-policy").get("get")
                .has("parameters")).isFalse();
        assertThat(document.get("paths").has("/api/v1/my-deliveries/{id}/events")).isTrue();
        assertThat(document.get("paths").has("/api/v1/skus/resolve")).isTrue();
        assertThat(document.get("paths").has("/api/v1/inventory/lots/resolve")).isTrue();
        String temperatureReadingsPath = "/api/v1/inventory/lots/{lotId}/temperature-readings";
        assertThat(document.get("paths").has(temperatureReadingsPath)).isTrue();
        assertResponseSchemaRef(document, temperatureReadingsPath, "get", "200",
                "#/components/schemas/TemperatureReadingsResponse");
        assertIntegerQueryParameterBounds(document, temperatureReadingsPath, "get", "page", 0, null);
        assertIntegerQueryParameterBounds(document, temperatureReadingsPath, "get", "size", 1, 100);
        assertThat(document.get("paths").get(temperatureReadingsPath).has("post")).isFalse();
        assertThat(document.at("/paths/~1api~1v1~1inventory~1lots~1{lotId}~1temperature-readings/get/description")
                .asText()).contains("inventory.temperature.audit.read", "source type RECEIVING",
                "evaluations are excluded", "no write operation");
        assertThat(document.at("/components/schemas/TemperatureReadingsResponse/properties/receiving/$ref").asText())
                .isEqualTo("#/components/schemas/ReceivingTemperatureReadingResponse");
        assertThat(document.at("/components/schemas/TemperatureReadingsResponse/properties/evaluations/$ref").asText())
                .isEqualTo("#/components/schemas/PageResponseTemperatureEvaluationResponse");
        assertThat(document.at("/components/schemas/TemperatureEvaluationResponse/properties/valueCelsius").isMissingNode())
                .isFalse();
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
        assertHeaderDescription(document, "/api/v1/warehouses/{warehouseId}/access-grants", "post", "If-Match",
                "Required only to reactivate a revoked grant; omit for a new grant.");
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
        assertThat(document.at("/paths/~1api~1v1~1deliveries~1{deliveryId}~1pod/get/operationId").asText())
                .isEqualTo("getCurrentDeliveryProofOfDelivery");
        assertResponseSchemaRef(document, "/api/v1/deliveries/{deliveryId}/pod", "get", "200",
                "#/components/schemas/PodView");
        assertThat(document.get("paths").has("/api/v1/delivery-handoff/validations")).isTrue();
        assertThat(document.get("paths").has("/api/v1/delivery-handoff/receipts")).isTrue();
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
        assertRequiredHeader(document, "/api/v1/delivery-handoff/receipts", "post", "Idempotency-Key");
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
        var creditConfiguration = document.get("paths")
                .get("/api/v1/client-accounts/{clientAccountId}/credit-account").get("put");
        var creditIfMatch = parameter(creditConfiguration, "If-Match");
        var creditIfNoneMatch = parameter(creditConfiguration, "If-None-Match");
        assertThat(creditIfMatch.get("required").asBoolean()).isFalse();
        assertThat(creditIfMatch.get("description").asText()).contains("updating an existing");
        assertThat(creditIfNoneMatch.get("required").asBoolean()).isFalse();
        assertThat(creditIfNoneMatch.get("description").asText()).contains("creating the first");
        assertThat(paymentIntent.get("responses").get("503").get("content").get("application/problem+json")
                .get("schema").get("$ref").asText()).isEqualTo("#/components/schemas/NexaProblemDetail");
        var buyerClientAccountNotFound = document.get("paths").get("/api/v1/client-accounts/me")
                .get("get").get("responses").get("404").get("content").get("application/problem+json")
                .get("schema").get("$ref").asText();
        assertThat(buyerClientAccountNotFound).isEqualTo("#/components/schemas/NexaProblemDetail");

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
		assertThat(document.at("/components/schemas/StreamingResponseBody/type").asText()).isEqualTo("string");
		assertThat(document.at("/components/schemas/StreamingResponseBody/format").asText()).isEqualTo("binary");
		assertResponseSchemaRef(document, "/api/v1/business-documents/{documentId}/downloads", "get", "200",
				"#/components/schemas/StreamingResponseBody");
		assertResponseSchemaRef(document, "/api/v1/business-document-evidence/{evidenceId}/downloads", "get", "200",
				"#/components/schemas/StreamingResponseBody");

        if (Boolean.getBoolean("nexa.openapi.write-snapshot")) {
            Files.writeString(snapshotPath, document.toString() + System.lineSeparator());
        }
        var committed = tools.jackson.databind.json.JsonMapper.shared().readTree(Files.readString(snapshotPath));
        assertThat(canonical(document)).as("runtime OpenAPI must equal committed canonical snapshot")
                .isEqualTo(canonical(committed));
    }

    private static tools.jackson.databind.JsonNode parameter(tools.jackson.databind.JsonNode operation, String name) {
        for (tools.jackson.databind.JsonNode parameter : operation.get("parameters")) {
            if (name.equals(parameter.path("name").asText()) && "header".equals(parameter.path("in").asText())) {
                return parameter;
            }
        }
        throw new AssertionError("Missing header parameter " + name);
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

    private static void assertNullableStringProperty(tools.jackson.databind.JsonNode document, String schema,
                                                      String property) {
        var value = document.at("/components/schemas/" + schema + "/properties/" + property);
        assertThat(value).as("nullable property %s.%s", schema, property).isNotNull();
        var type = value.get("type");
        boolean nullableType = type != null && type.isArray() && type.toString().contains("\"null\"");
        if (!nullableType) {
            var alternatives = value.get("anyOf");
            assertThat(alternatives).as("nullable property %s.%s must have a JSON Schema null branch", schema, property)
                    .isNotNull();
            nullableType = alternatives.toString().contains("\"type\":\"null\"")
                    || alternatives.toString().contains("\"type\":[\"null\"");
        }
        assertThat(nullableType).as("nullable property %s.%s must permit null", schema, property).isTrue();
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

    private static void assertOperationResponse(tools.jackson.databind.JsonNode document, String operationId,
                                                String status, boolean alsoHasOk) {
        var operation = findOperation(document, operationId);
        var responses = operation.get("responses");
        assertThat(responses.has(status)).as("%s publishes its implemented %s response", operationId, status).isTrue();
        assertThat(responses.has("200")).as("%s does not publish an incorrect 200 response", operationId)
                .isEqualTo(alsoHasOk);
    }

    private static void assertOperationResponseHeader(tools.jackson.databind.JsonNode document, String operationId,
                                                      String status, String header) {
        assertThat(findOperation(document, operationId).at("/responses/" + status + "/headers/" + header).isObject())
                .as("%s %s response documents %s", operationId, status, header).isTrue();
    }

    private static void assertCurrentVersionEtags(tools.jackson.databind.JsonNode document) {
        assertThat(CURRENT_VERSION_ETAG_OPERATIONS).hasSize(146);
        assertThat(CURRENT_VERSION_ETAG_OPERATIONS)
                .doesNotContain("POST /api/v1/client-accounts/{customerId}/field-visits#recordCustomerFieldVisitEvidence");

        for (String key : CURRENT_VERSION_ETAG_OPERATIONS) {
            var operation = findOperationByContractKey(document, key);
            var responses = operation.path("responses");
            var successResponses = responses.properties().stream()
                    .filter(response -> response.getKey().matches("2\\d{2}"))
                    .toList();
            assertThat(successResponses).as("%s declares at least one 2xx response", key).isNotEmpty();
            successResponses.forEach(response -> {
                var etag = response.getValue().path("headers").path("ETag");
                assertThat(etag.isObject())
                        .as("%s %s response documents ETag", key, response.getKey()).isTrue();
                assertThat(etag.path("schema").path("type").asText())
                        .as("%s %s ETag is a string", key, response.getKey()).isEqualTo("string");
            });
        }
    }

    private static void assertReplayableCreatedResponses(tools.jackson.databind.JsonNode document) {
        assertThat(REPLAYABLE_CREATED_OPERATIONS).hasSize(27);
        for (String key : REPLAYABLE_CREATED_OPERATIONS) {
            assertOperationSuccessStatuses(document, key, Set.of("200", "201"));
            var responses = findOperationByContractKey(document, key).path("responses");
            boolean explicitlyDocumentedHandoff = key.equals(
                    "POST /api/v1/delivery-handoff/receipts#acceptBuyerDeliveryHandoff");
            String expectedCreatedDescription = explicitlyDocumentedHandoff
                    ? "Buyer receipt accepted and linked Proof of Delivery finalized"
                    : "Created on first execution";
            assertThat(responses.path("201").path("description").asText())
                    .as("%s preserves its first-execution 201 response", key)
                    .isEqualTo(expectedCreatedDescription);
            if (explicitlyDocumentedHandoff) {
                assertThat(responses.path("200").path("description").asText())
                        .as("%s retains its explicit replay response", key)
                        .isEqualTo("Idempotent replay of Buyer acceptance");
            }
            assertThat(responses.path("201").path("content"))
                    .as("%s creation response keeps the replay representation", key)
                    .isEqualTo(responses.path("200").path("content"));
        }
    }

    private static void assertOperationSuccessStatuses(tools.jackson.databind.JsonNode document, String key,
                                                       Set<String> expectedStatuses) {
        var responses = findOperationByContractKey(document, key).path("responses");
        Set<String> actualStatuses = new HashSet<>();
        responses.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .filter(response -> response.matches("2\\d{2}"))
                .forEach(actualStatuses::add);
        assertThat(actualStatuses)
                .as("%s declares exactly the implemented 2xx statuses", key)
                .isEqualTo(expectedStatuses);
    }

    private static tools.jackson.databind.JsonNode findOperationByContractKey(
            tools.jackson.databind.JsonNode document, String key) {
        int methodEnd = key.indexOf(' ');
        int operationStart = key.indexOf('#');
        assertThat(methodEnd).as("Contract key has an HTTP method: %s", key).isGreaterThan(0);
        assertThat(operationStart).as("Contract key has an operation ID: %s", key).isGreaterThan(methodEnd);

        String method = key.substring(0, methodEnd);
        String path = key.substring(methodEnd + 1, operationStart);
        String operationId = key.substring(operationStart + 1);
        var operation = document.path("paths").path(path).path(method.toLowerCase(java.util.Locale.ROOT));
        assertThat(operation.isObject()).as("OpenAPI operation exists: %s", key).isTrue();
        assertThat(operation.path("operationId").asText()).as("OpenAPI operation ID matches: %s", key)
                .isEqualTo(operationId);
        return operation;
    }

    private static tools.jackson.databind.JsonNode findOperation(tools.jackson.databind.JsonNode document,
                                                                 String operationId) {
        return document.get("paths").properties().stream()
                .flatMap(path -> path.getValue().properties().stream())
                .map(operation -> operation.getValue())
                .filter(operation -> operationId.equals(operation.path("operationId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("OpenAPI operation is missing: " + operationId));
    }

    private static void assertOperationHeaderRequired(tools.jackson.databind.JsonNode document, String operationId,
                                                      String headerName, boolean expectedRequired) {
        var parameters = findOperation(document, operationId).path("parameters");
        var matchingHeaders = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        for (tools.jackson.databind.JsonNode parameter : parameters) {
            if ("header".equalsIgnoreCase(parameter.path("in").asText())
                    && headerName.equalsIgnoreCase(parameter.path("name").asText())) {
                matchingHeaders.add(parameter);
            }
        }
        assertThat(matchingHeaders).as("%s declares exactly one %s header", operationId, headerName).hasSize(1);
        assertThat(matchingHeaders.get(0).path("required").asBoolean())
                .as("%s header %s required=%s in OpenAPI", operationId, headerName, expectedRequired)
                .isEqualTo(expectedRequired);
    }

    private static void assertSecurityRequirement(tools.jackson.databind.JsonNode document, String operationId,
                                                  Set<String> requiredSchemes) {
        var requirements = findOperation(document, operationId).path("security");
        assertThat(requirements.isArray()).as("%s declares security requirements", operationId).isTrue();
        boolean found = false;
        for (tools.jackson.databind.JsonNode requirement : requirements) {
            Set<String> schemes = new HashSet<>();
            requirement.properties().forEach(entry -> schemes.add(entry.getKey()));
            if (schemes.equals(requiredSchemes)) found = true;
        }
        assertThat(found).as("%s requires exactly the scheme set %s", operationId, requiredSchemes).isTrue();
    }

    private static void assertApiKeySecurityScheme(tools.jackson.databind.JsonNode document, String scheme,
                                                   String headerName) {
        assertThat(document.at("/components/securitySchemes/" + scheme + "/type").asText()).isEqualTo("apiKey");
        assertThat(document.at("/components/securitySchemes/" + scheme + "/in").asText()).isEqualTo("header");
        assertThat(document.at("/components/securitySchemes/" + scheme + "/name").asText()).isEqualTo(headerName);
    }

    private static void assertOptionalBearerForSignOut(tools.jackson.databind.JsonNode document) {
        var requirements = findOperation(document, "signOut").path("security");
        assertThat(requirements.isArray()).isTrue();
        boolean bearer = false;
        boolean anonymous = false;
        for (tools.jackson.databind.JsonNode requirement : requirements) {
            bearer |= requirement.has("bearerAuth");
            anonymous |= requirement.isObject() && requirement.isEmpty();
        }
        assertThat(bearer).as("sign-out can revoke a session using Bearer authentication").isTrue();
        assertThat(anonymous).as("sign-out can clear the cookie without an authenticated session").isTrue();
    }

    private static void assertIfMatchRequiredWhenPreconditioned(tools.jackson.databind.JsonNode document) {
        document.get("paths").properties().stream().flatMap(path -> path.getValue().properties().stream())
                .map(entry -> entry.getValue())
                .filter(operation -> operation.path("responses").has("428"))
                .forEach(operation -> {
                    var parameters = operation.path("parameters");
                    var ifMatchHeaders = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
                    for (tools.jackson.databind.JsonNode parameter : parameters) {
                        if ("header".equalsIgnoreCase(parameter.path("in").asText())
                                && "If-Match".equalsIgnoreCase(parameter.path("name").asText())) {
                            ifMatchHeaders.add(parameter);
                        }
                    }
                    String operationId = operation.path("operationId").asText();
                    assertThat(ifMatchHeaders).as("%s declares exactly one If-Match header with 428", operationId)
                            .hasSize(1);
                    boolean expectedRequired = !Set.of("grantWarehouseAccess", "configureClientCreditAccount")
                            .contains(operationId);
                    assertThat(ifMatchHeaders.get(0).path("required").asBoolean())
                            .as("%s If-Match required=%s in OpenAPI", operationId, expectedRequired)
                            .isEqualTo(expectedRequired);
                });
    }

    private static void assertRequiredHeader(tools.jackson.databind.JsonNode document, String path,
                                             String method, String name) {
        assertHeaderRequired(document, path, method, name, true);
    }

    private static void assertHeaderDescription(tools.jackson.databind.JsonNode document, String path,
                                                String method, String name, String description) {
        boolean matches = false;
        for (tools.jackson.databind.JsonNode parameter : document.get("paths").get(path).get(method).get("parameters")) {
            if (name.equals(parameter.path("name").asText()) && "header".equals(parameter.path("in").asText())) {
                matches = description.equals(parameter.path("description").asText());
            }
        }
        assertThat(matches).as("%s %s header %s documents its conditional requirement", method, path, name).isTrue();
    }

    private static void assertIntegerQueryParameterBounds(tools.jackson.databind.JsonNode document, String path,
                                                          String method, String name, int minimum, Integer maximum) {
        tools.jackson.databind.JsonNode match = null;
        for (tools.jackson.databind.JsonNode parameter : document.get("paths").get(path).get(method).get("parameters")) {
            if (name.equals(parameter.path("name").asText()) && "query".equals(parameter.path("in").asText())) {
                match = parameter;
                break;
            }
        }
        assertThat(match).as("%s %s query parameter %s is documented", method, path, name).isNotNull();
        assertThat(match.path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(match.path("schema").path("minimum").asInt()).isEqualTo(minimum);
        if (maximum == null) {
            assertThat(match.path("schema").has("maximum")).isFalse();
        } else {
            assertThat(match.path("schema").path("maximum").asInt()).isEqualTo(maximum);
        }
    }

    private static void assertQueryParameterDescription(tools.jackson.databind.JsonNode document, String path,
                                                        String method, String name, String description) {
        tools.jackson.databind.JsonNode match = null;
        for (tools.jackson.databind.JsonNode parameter : document.get("paths").get(path).get(method).get("parameters")) {
            if (name.equals(parameter.path("name").asText()) && "query".equals(parameter.path("in").asText())) {
                match = parameter;
                break;
            }
        }
        assertThat(match).as("%s %s query parameter %s is documented", method, path, name).isNotNull();
        assertThat(match.path("description").asText())
                .as("%s %s query parameter %s documents its filter semantics", method, path, name)
                .isEqualTo(description);
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

    private static void assertDeprecated(tools.jackson.databind.JsonNode document, String path, String method) {
        var deprecated = document.get("paths").get(path).get(method).get("deprecated");
        assertThat(deprecated).as("%s %s has deprecated metadata", method, path).isNotNull();
        assertThat(deprecated.asBoolean()).as("%s %s is a compatibility alias", method, path).isTrue();
    }

    private static void assertNoContentResponse(tools.jackson.databind.JsonNode document, String path, String method) {
        var responses = document.get("paths").get(path).get(method).get("responses");
        assertThat(responses.has("204")).as("%s %s returns 204", method, path).isTrue();
        assertThat(responses.has("200")).as("%s %s does not publish 200", method, path).isFalse();
    }

    private static void assertConditionalNoContentResponse(tools.jackson.databind.JsonNode document, String path,
                                                           String method) {
        var responses = document.get("paths").get(path).get(method).get("responses");
        assertThat(responses.has("200")).as("%s %s can return its current resource", method, path).isTrue();
        assertThat(responses.has("204")).as("%s %s documents the absent-resource result", method, path).isTrue();
    }
}
