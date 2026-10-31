package com.nexa.api.edge.http.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashMap;

@Configuration(proxyBeanMethods = false)
@Conditional(OpenApiConfiguration.OpenApiEnabledCondition.class)
public class OpenApiConfiguration {
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

	private static final Set<String> RUNTIME_REQUIRED_IDEMPOTENCY_OPERATIONS = Set.of(
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
			"acceptBuyerDeliveryHandoff", "recordBuyerDeliveryReceipt",
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
			"assign", "publishOperationalDeliveryInstruction");

	static class OpenApiEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			Environment env = context.getEnvironment();
			return env.acceptsProfiles(Profiles.of("local"))
					|| Boolean.parseBoolean(env.getProperty("nexa.swagger.enabled", "false"))
					|| Boolean.parseBoolean(env.getProperty("springdoc.swagger-ui.enabled", "false"))
					|| Boolean.parseBoolean(env.getProperty("springdoc.api-docs.enabled", "false"));
		}
	}
	@Bean
	ModelConverter applicationResponseSchemaNames() {
		return new ModelConverter() {
			private final Map<String, String> schemaNames = Map.ofEntries(
					Map.entry("com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels$Line",
							"OutgoingGoodsCheckLine"),
					Map.entry("com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels$Line",
							"ExecutionTemperatureLine"),
					Map.entry("com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels$Snapshot",
							"ExecutionTemperatureSnapshot"),
					Map.entry("com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels$Reading",
							"ExecutionTemperatureReading"),
					Map.entry("com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels$Hold",
							"ExecutionTemperatureHold"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$DraftPage",
							"BuyerPurchaseRequestDraftPage"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$DraftSummaryView",
							"BuyerPurchaseRequestDraftSummaryView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$DraftView",
							"BuyerPurchaseRequestDraftView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$LineView",
							"BuyerPurchaseRequestDraftLineView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$DestinationView",
							"BuyerPurchaseRequestDraftDestinationView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$RouteView",
							"BuyerPurchaseRequestDraftRouteView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$WarehouseSelectionView",
							"BuyerPurchaseRequestDraftWarehouseSelectionView"),
					Map.entry("com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels$ReviewView",
							"BuyerPurchaseRequestReviewView"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$CreateDraftRequest",
							"BuyerPurchaseRequestDraftCreateRequest"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$LinesRequest",
							"BuyerPurchaseRequestDraftLinesRequest"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$LineRequest",
							"BuyerPurchaseRequestDraftLineRequest"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$DestinationRequest",
							"BuyerPurchaseRequestDraftDestinationRequest"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$RouteRequest",
							"BuyerPurchaseRequestDraftRouteRequest"),
					Map.entry("com.nexa.api.salescommitment.presentation.purchaserequestdraft.PurchaseRequestDraftController$PreferencesRequest",
							"BuyerPurchaseRequestDraftPreferencesRequest"));

			@Override
			public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
					Iterator<ModelConverter> chain) {
				if (type != null && type.getType() != null) {
					String typeName = Json.mapper().constructType(type.getType()).getRawClass().getName();
					String schemaName = schemaNames.get(typeName);
					if (schemaName != null) type.name(schemaName);
				}
				return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
			}
		};
	}

	@Bean
	OpenAPI nexaOpenAPI(ObjectProvider<BuildProperties> buildProperties,
			@Value("${spring.application.version:unknown}") String configuredVersion) {
		BuildProperties packagedBuild = buildProperties.getIfAvailable();
		String version = packagedBuild == null ? configuredVersion : packagedBuild.getVersion();
		return new OpenAPI()
				.info(new Info().title("Nexa API").version(version).description(
						"Local API contract for secured identity, tenant/workspace administration, Catalog reads and "
								+ "Sales Client Accounts and Purchase Requests. Bearer authentication revalidates the "
								+ "persistent session; browser refresh and sign-out use the surface refresh cookie and Origin policy, "
								+ "while the explicit NATIVE transport uses a rotated opaque refresh-token header without weakening "
								+ "the browser Origin boundary. "
								+ "Sales Order conversion and lifecycle endpoints plus the tenant-scoped persisted change feed are included. "
						+ "Warehouse and Logistics HTTP workflows are included with FEFO, dispatch lifecycle and Proof of Delivery evidence. "
						+ "Business Documents, private object storage, invoice drafts, receivables and Stripe-compatible payment intents are included."))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
								.scheme("bearer").bearerFormat("JWT").description("RS256 access token"))
						.addSecuritySchemes("refreshCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.COOKIE).name("NEXA_PLATFORM_REFRESH")
							.description("HttpOnly SameSite=Strict refresh cookie; Portal uses NEXA_PORTAL_REFRESH"))
						.addSecuritySchemes("nativeRefreshToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.HEADER).name("X-Nexa-Refresh-Token")
							.description("Rotated opaque token for the explicit NATIVE transport; pair with X-Nexa-Client: NATIVE"))
						.addSecuritySchemes("contextTicket", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.HEADER).name("X-Nexa-Context-Ticket")
							.description("Opaque, short-lived pre-context credential; pair with X-Nexa-Client: NATIVE"))
						.addSecuritySchemes("systemOperator", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.HEADER).name("X-Nexa-System-Operator")
							.description("System-operator credential for internal organization-registration decisions"))
						.addSecuritySchemes("internalOperatorId", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.HEADER).name("X-Nexa-Internal-Operator-Id")
							.description("Allowlisted internal-operator identifier; required with internalOperatorToken"))
						.addSecuritySchemes("internalOperatorToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.HEADER).name("X-Nexa-Internal-Operator-Token")
							.description("Internal-operator credential; required with internalOperatorId")));
	}

	@Bean
	GlobalOpenApiCustomizer observedRuntimeSecurityContract() {
		Set<String> bearerOperations = Set.of("driverRead", "record", "dispose", "listCustomerFieldVisitEvidence",
				"recordCustomerFieldVisitEvidence", "dispositionRead", "listVisibleCatalogFilterOptions");
		Set<String> systemOperatorOperations = Set.of("activate", "rejectOrganizationRegistration");
		Set<String> internalOperatorOperations = Set.of("listInternalSupportRequests", "requestTemporarySalesOrderSupport",
				"revokeTemporarySalesOrderSupportByOperator", "approveTemporarySalesOrderSupport",
				"readSalesOrderUnderSupportGrant", "listInternalOnboardingHealth");
		return openAPI -> {
			openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream()).forEach(operation -> {
				String operationId = operation.getOperationId();
				if (bearerOperations.contains(operationId)) {
					operation.setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement()
							.addList("bearerAuth")));
				} else if (systemOperatorOperations.contains(operationId)) {
					operation.setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement()
							.addList("systemOperator")));
				} else if (internalOperatorOperations.contains(operationId)) {
					operation.setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement()
							.addList("internalOperatorId").addList("internalOperatorToken")));
				} else if ("signOut".equals(operationId)) {
					// Runtime permits anonymous cookie cleanup; Bearer is needed to revoke the server session.
					operation.setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement()
							.addList("bearerAuth"), new io.swagger.v3.oas.models.security.SecurityRequirement()));
					operation.setDescription("Bearer authentication revokes the current server session. An anonymous request may clear the browser refresh cookie without claiming that a server-side session was revoked.");
				}
			});
		};
	}

	@Bean
	GlobalOpenApiCustomizer organizationDraftResumeTokenContract() {
		Map<String, String> operations = Map.of(
				"getOrganizationRegistrationDraft", "Read the draft using its resume token; accepted compatibility alias: X-Organization-Registration-Token.",
				"updateOrganizationRegistrationDraftStep", "Authorize draft access using its resume token; accepted compatibility alias: X-Organization-Registration-Token.",
				"submitOrganizationRegistrationDraft", "Authorize draft submission using its resume token; accepted compatibility alias: X-Organization-Registration-Token.");
		return openAPI -> {
			Set<String> found = new java.util.HashSet<>();
			openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream())
					.filter(operation -> operations.containsKey(operation.getOperationId()))
					.forEach(operation -> {
						List<io.swagger.v3.oas.models.parameters.Parameter> parameters = operation.getParameters() == null
								? new java.util.ArrayList<>() : new java.util.ArrayList<>(operation.getParameters());
						List<io.swagger.v3.oas.models.parameters.Parameter> matching = parameters.stream()
								.filter(parameter -> "header".equalsIgnoreCase(parameter.getIn())
										&& "X-Resume-Token".equalsIgnoreCase(parameter.getName())).toList();
						if (matching.size() > 1) {
							throw new IllegalStateException("Duplicate X-Resume-Token header for " + operation.getOperationId());
						}
						io.swagger.v3.oas.models.parameters.Parameter tokenHeader = matching.isEmpty()
								? new io.swagger.v3.oas.models.parameters.Parameter().name("X-Resume-Token").in("header")
								: matching.get(0);
						tokenHeader.required(true).description(operations.get(operation.getOperationId()))
								.schema(new StringSchema());
						if (matching.isEmpty()) parameters.add(tokenHeader);
						operation.setParameters(parameters);
						found.add(operation.getOperationId());
					});
		};
	}

	/**
	 * Keeps streamed document downloads on a reusable binary response schema.
	 * Springdoc currently inlines the controller's InputStreamResource as a string;
	 * this runtime contract mapping preserves the public StreamingResponseBody component.
	 * Narrow OpenAPI test contexts may omit these controllers; OpenApiContractIT verifies both
	 * routes in the complete runtime contract.
	 */
	@Bean
	GlobalOpenApiCustomizer streamedBusinessDocumentDownloadSchema() {
		return openAPI -> {
			Components components = openAPI.getComponents() == null ? new Components() : openAPI.getComponents();
			openAPI.setComponents(components);
			components.addSchemas("StreamingResponseBody", new StringSchema()
					.format("binary")
					.description("Binary content streamed by an authorized business-document download."));

			for (String path : List.of(
					"/api/v1/business-documents/{documentId}/downloads",
					"/api/v1/business-document-evidence/{evidenceId}/downloads")) {
				var pathItem = openAPI.getPaths() == null ? null : openAPI.getPaths().get(path);
				if (pathItem == null) continue;
				var operation = pathItem.getGet();
				ApiResponse response = operation == null || operation.getResponses() == null
						? null : operation.getResponses().get("200");
				if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
					throw new IllegalStateException("Business-document download response is unavailable: " + path);
				}
				response.getContent().values().forEach(mediaType ->
						mediaType.setSchema(new Schema<>().$ref("#/components/schemas/StreamingResponseBody")));
			}
		};
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

	@Bean
	GlobalOpenApiCustomizer nullableCatalogPriceProperties() {
		return openAPI -> {
			Map<String, Schema> schemas = openAPI.getComponents() == null ? null : openAPI.getComponents().getSchemas();
			if (schemas == null) return;
			for (String response : List.of("CatalogItemSummaryResponse", "CatalogItemDetailResponse")) {
				Schema<?> schema = schemas.get(response);
				if (schema == null) continue;
				if (schema.getProperties() == null) throw new IllegalStateException("Catalog response has no properties: " + response);
				for (String property : List.of("basePrice", "currentOfferPrice")) {
					Object existing = schema.getProperties().get(property);
					if (!(existing instanceof Schema<?> reference) || reference.get$ref() == null) {
						throw new IllegalStateException("Catalog price schema reference is unavailable: "
								+ response + "." + property);
					}
					List<Schema> alternatives = new java.util.ArrayList<>();
					alternatives.add(new Schema<>().$ref(reference.get$ref()));
					alternatives.add(new JsonSchema().types(java.util.Set.of("null")));
					schema.addProperty(property, new ComposedSchema().anyOf(alternatives));
				}
			}
		};
	}

	@Bean
	GlobalOpenApiCustomizer nullableBuyerCatalogFavoriteOffer() {
		return openAPI -> {
			Map<String, Schema> schemas = openAPI.getComponents() == null ? null : openAPI.getComponents().getSchemas();
			if (schemas == null) return;
			Schema<?> schema = schemas.get("BuyerCatalogFavoriteResponse");
			if (schema == null) return;
			if (schema.getProperties() == null) {
				throw new IllegalStateException("Buyer favorite response has no properties");
			}
			Object existing = schema.getProperties().get("currentOffer");
			if (!(existing instanceof Schema<?> reference) || reference.get$ref() == null) {
				throw new IllegalStateException("Buyer favorite current-offer schema reference is unavailable");
			}
			ComposedSchema nullableOffer = new ComposedSchema();
			nullableOffer.setAnyOf(List.of(new Schema<>().$ref(reference.get$ref()),
					new JsonSchema().types(Set.of("null"))));
			nullableOffer.setDescription(reference.getDescription());
			schema.addProperty("currentOffer", nullableOffer);
		};
	}

	@Bean
	GlobalOpenApiCustomizer observedHttpResponseContract() {
		Set<String> createdOperations = Set.of(
				"createReceivable", "createReceivablePaymentIntent", "createCreditLinePayment", "createBankTransferPayment",
				"createBuyerWalletRecharge", "requestBusinessDocumentEvidence", "uploadBusinessDocumentEvidence",
				"submitFieldPurchaseRequest", "createPurchaseRequestDraft", "createManualSalesOrderDraft",
				"submitManualSalesOrderDraft", "createManualSalesOrder", "recordInventoryCycleCount",
				"createInventoryTransfer", "recordInventoryTransferReceiptObservation", "createOrganizationInvitation",
				"acceptOrganizationInvitation", "createRoleDefinition", "requestTemporarySalesOrderSupport",
				"createDispatchDeliveryLoad", "startFulfillment", "captureDeliveryProofOfDelivery",
				"recordDeliveryTemperatureEvidence", "recordStockTemperatureEvidence",
				"recordFulfillmentTemperatureEvidence", "createDispatchOrder", "createCatalogPromotion",
				"createProductFamily", "createProductVariant", "createSellableSku", "registerNativePushSubscription",
				"createClientAccountAddress", "recordCustomerFieldVisitEvidence", "createWarehouse", "createZone",
				"receive", "createCustomField", "createWorkspace", "appendHandoffNote", "createCategory", "createBrand",
				"reserve");
		Set<String> locationOperations = Set.of(
				"createReceivable", "createReceivablePaymentIntent", "createCreditLinePayment", "createBankTransferPayment",
				"createBuyerWalletRecharge", "requestBusinessDocumentEvidence", "uploadBusinessDocumentEvidence",
				"createPurchaseRequestDraft", "createManualSalesOrderDraft", "submitManualSalesOrderDraft",
				"createManualSalesOrder", "createProductFamily", "createProductVariant", "createSellableSku");
		Set<String> etagOperations = Set.of(
				"createPurchaseRequestDraft", "createManualSalesOrderDraft", "submitManualSalesOrderDraft",
				"createManualSalesOrder", "createProductFamily", "createSellableSku", "submitFieldPurchaseRequest",
				"createClientAccountAddress", "createCategory", "createBrand", "createRoleDefinition", "createWorkspace",
				"createWarehouse", "createZone", "receive", "recordInventoryCycleCount", "createInventoryTransfer",
				"recordInventoryTransferReceiptObservation", "createOrganizationInvitation", "createCustomField",
				"createDispatchDeliveryLoad", "appendHandoffNote", "createDispatchOrder", "startFulfillment",
				"captureDeliveryProofOfDelivery", "recordDeliveryTemperatureEvidence",
				"recordFulfillmentTemperatureEvidence", "createCatalogPromotion", "reserve");
		Set<String> buyerDraftEtagOperations = Set.of(
				"getPurchaseRequestDraft",
				"replacePurchaseRequestDraftLines", "setPurchaseRequestDraftDestination",
				"previewPurchaseRequestDraftRoute", "setPurchaseRequestDraftPreferences", "submitPurchaseRequestDraft");
		Set<String> acceptedOperations = Set.of(
				"submitPublicContactRequest", "requestBusinessDocumentGeneration", "regenerateBusinessDocument",
				"replaceBusinessDocument", "receiveStripeWebhook");
		Set<String> acceptedLocationOperations = Set.of(
				"requestBusinessDocumentGeneration", "regenerateBusinessDocument", "replaceBusinessDocument");
		return openAPI -> {
			Set<String> foundCreated = new java.util.HashSet<>();
			Set<String> foundAccepted = new java.util.HashSet<>();
			Set<String> foundBuyerDraftEtags = new java.util.HashSet<>();
			openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream()).forEach(operation -> {
				String operationId = operation.getOperationId();
				if (buyerDraftEtagOperations.contains(operationId)) {
					ApiResponse response = operation.getResponses() == null ? null : operation.getResponses().get("200");
					if (response == null) throw new IllegalStateException("Missing 200 response for " + operationId);
					response.addHeaderObject("ETag", new Header().description("Current draft version ETag")
							.schema(new StringSchema()));
					foundBuyerDraftEtags.add(operationId);
				} else if (createdOperations.contains(operationId)) {
					moveResponse(operation, "200", "201", "Resource created",
							locationOperations.contains(operationId), etagOperations.contains(operationId));
					foundCreated.add(operationId);
				} else if (acceptedOperations.contains(operationId)) {
					moveResponse(operation, "200", "202", "Request accepted for asynchronous processing",
							acceptedLocationOperations.contains(operationId), false);
					foundAccepted.add(operationId);
				}
			});
			Set<String> foundReplayableCreated = new java.util.HashSet<>();
			openAPI.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
				String key = method.name() + " " + path + "#" + operation.getOperationId();
				if (!REPLAYABLE_CREATED_OPERATIONS.contains(key)) return;
				addCreatedReplayResponse(operation);
				foundReplayableCreated.add(key);
			}));
			addNoContentResponses(openAPI);
			requireIfMatchWhenPreconditioned(openAPI);
			requireRuntimeIdempotencyKeys(openAPI);
		};
	}

	@Bean
	GlobalOpenApiCustomizer currentVersionEtagResponses() {
		return openAPI -> {
			Set<String> found = new java.util.HashSet<>();
			openAPI.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
				String key = method.name() + " " + path + "#" + operation.getOperationId();
				if (!CURRENT_VERSION_ETAG_OPERATIONS.contains(key)) return;
				ApiResponses responses = operation.getResponses();
				if (responses == null) {
					throw new IllegalStateException("Missing responses for current-version ETag operation " + key);
				}
				List<String> successStatuses = responses.keySet().stream()
						.filter(status -> status.matches("2\\d{2}")).toList();
				if (successStatuses.isEmpty()) {
					throw new IllegalStateException("Missing 2xx response for current-version ETag operation " + key);
				}
				for (String status : successStatuses) {
					ApiResponse response = responses.get(status);
					if (response.get$ref() != null) {
						throw new IllegalStateException("Expected inline " + status + " response for current-version ETag operation " + key);
					}
					response.addHeaderObject("ETag", new Header().description("Current resource version ETag")
							.schema(new StringSchema()));
				}
				found.add(key);
			}));
		};
	}

	private static void moveResponse(Operation operation, String sourceStatus, String targetStatus,
			String description, boolean location, boolean etag) {
		ApiResponses responses = operation.getResponses();
		if (responses == null) throw new IllegalStateException("Missing responses for " + operation.getOperationId());
		ApiResponse source = responses.get(sourceStatus);
		if (source == null) {
			if (responses.get(targetStatus) != null) return;
			throw new IllegalStateException("Missing " + sourceStatus + " response for " + operation.getOperationId());
		}
		ApiResponse target = new ApiResponse().description(description).content(source.getContent());
		if (source.getHeaders() != null) target.setHeaders(new LinkedHashMap<>(source.getHeaders()));
		if (location) target.addHeaderObject("Location", new Header().description("URI reference of the created resource")
				.schema(new StringSchema().format("uri-reference")));
		if (etag) target.addHeaderObject("ETag", new Header().description("Current entity version ETag")
				.schema(new StringSchema()));
		responses.remove(sourceStatus);
		responses.addApiResponse(targetStatus, target);
	}

	private static void addCreatedReplayResponse(Operation operation) {
		ApiResponses responses = operation.getResponses();
		if (responses == null) throw new IllegalStateException("Missing responses for " + operation.getOperationId());
		ApiResponse replay = responses.get("200");
		if (replay == null) throw new IllegalStateException("Missing 200 replay response for " + operation.getOperationId());
		if (replay.get$ref() != null) throw new IllegalStateException("Expected inline 200 replay response for " + operation.getOperationId());
		if (responses.containsKey("201")) return;
		ApiResponse created = new ApiResponse().description("Created on first execution").content(replay.getContent());
		if (replay.getHeaders() != null) created.setHeaders(new LinkedHashMap<>(replay.getHeaders()));
		if (replay.getLinks() != null) created.setLinks(new LinkedHashMap<>(replay.getLinks()));
		if (replay.getExtensions() != null) created.setExtensions(new LinkedHashMap<>(replay.getExtensions()));
		responses.addApiResponse("201", created);
	}

	private static void addNoContentResponses(OpenAPI openAPI) {
		Map<String, String> unconditional = Map.of(
				"markRead", "No response body; notification marked read",
				"markUnread", "No response body; notification marked unread",
				"markAllRead", "No response body; notifications marked read",
				"deleteBusinessDocumentEvidence", "No response body; evidence deleted",
				"unregisterNativePushSubscription", "No response body; push subscription removed");
		Map<String, String> conditional = Map.of(
				"getPreparedFulfillmentDriverAssignment", "No current driver assignment is available",
				"getCurrentDriverWorkday", "No current Driver workday is available",
				"getCurrentDeliveryProofOfDelivery", "No current Proof of Delivery is available",
				"getCurrentOutgoingGoodsCheck", "No current outgoing-goods check is available",
				"getCurrentDispatchOutgoingGoodsCheckSummary", "No current dispatch outgoing-check summary is available");
		Set<String> foundUnconditional = new java.util.HashSet<>();
		Set<String> foundConditional = new java.util.HashSet<>();
		openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream()).forEach(operation -> {
			String id = operation.getOperationId();
			if (unconditional.containsKey(id)) {
				operation.getResponses().remove("200");
				operation.getResponses().addApiResponse("204", new ApiResponse().description(unconditional.get(id)));
				foundUnconditional.add(id);
			} else if (conditional.containsKey(id)) {
				operation.getResponses().addApiResponse("204", new ApiResponse().description(conditional.get(id)));
				foundConditional.add(id);
			}
		});
	}

	private static void requireIfMatchWhenPreconditioned(OpenAPI openAPI) {
		openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream())
				.filter(operation -> operation.getResponses() != null && operation.getResponses().containsKey("428"))
				.forEach(operation -> {
					List<io.swagger.v3.oas.models.parameters.Parameter> headers = operation.getParameters() == null
							? List.of()
							: operation.getParameters().stream()
								.filter(parameter -> "header".equalsIgnoreCase(parameter.getIn())
										&& "If-Match".equalsIgnoreCase(parameter.getName()))
								.toList();
					if (headers.size() != 1) {
						throw new IllegalStateException("Expected one If-Match header for " + operation.getOperationId()
								+ " because it publishes 428, found " + headers.size());
					}
					var header = headers.get(0);
					if ("configureClientCreditAccount".equals(operation.getOperationId())) {
						List<io.swagger.v3.oas.models.parameters.Parameter> ifNoneMatchHeaders = operation.getParameters().stream()
								.filter(parameter -> "header".equalsIgnoreCase(parameter.getIn())
										&& "If-None-Match".equalsIgnoreCase(parameter.getName()))
								.toList();
						if (ifNoneMatchHeaders.size() != 1) {
							throw new IllegalStateException("Expected one If-None-Match header for "
									+ operation.getOperationId() + ", found " + ifNoneMatchHeaders.size());
						}
						header.setRequired(false);
						header.setDescription("Required when updating an existing credit account; omit when creating it.");
						ifNoneMatchHeaders.get(0).setRequired(false);
						ifNoneMatchHeaders.get(0).setDescription("Required with value * when creating the first credit account; omit when updating it.");
						return;
					}
					if ("grantWarehouseAccess".equals(operation.getOperationId())) {
						header.setDescription("Required only to reactivate a revoked grant; omit for a new grant.");
						return;
					}
					header.setRequired(true);
				});
	}

	private static void requireRuntimeIdempotencyKeys(OpenAPI openAPI) {
		Set<String> found = new java.util.HashSet<>();
		openAPI.getPaths().values().stream().flatMap(path -> path.readOperations().stream())
				.filter(operation -> RUNTIME_REQUIRED_IDEMPOTENCY_OPERATIONS.contains(operation.getOperationId()))
				.forEach(operation -> {
					List<io.swagger.v3.oas.models.parameters.Parameter> headers = operation.getParameters() == null
							? List.of()
							: operation.getParameters().stream()
								.filter(parameter -> "header".equalsIgnoreCase(parameter.getIn())
										&& "Idempotency-Key".equalsIgnoreCase(parameter.getName()))
								.toList();
					if (headers.size() != 1) {
						throw new IllegalStateException("Expected one Idempotency-Key header for "
								+ operation.getOperationId() + ", found " + headers.size());
					}
					headers.get(0).setRequired(true);
					found.add(operation.getOperationId());
				});
	}

	private static Set<String> difference(Set<String> expected, Set<String> actual) {
		Set<String> missing = new java.util.HashSet<>(expected);
		missing.removeAll(actual);
		return missing;
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
			if ("428".equals(status) && !requiresPrecondition(operation)) {
				return;
			}
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

	private static boolean requiresPrecondition(Operation operation) {
		return operation.getParameters() != null && operation.getParameters().stream()
				.anyMatch(parameter -> "header".equalsIgnoreCase(parameter.getIn())
						&& "If-Match".equalsIgnoreCase(parameter.getName()));
	}

	private static MediaType problemMediaType() {
		return new MediaType().schema(new Schema<>().$ref("#/components/schemas/NexaProblemDetail"));
	}
}
