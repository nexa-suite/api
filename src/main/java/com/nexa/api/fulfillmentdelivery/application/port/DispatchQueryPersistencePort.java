package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchSalesOwnerScope;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface DispatchQueryPersistencePort {
    LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(String tenantId, String workspaceId, String clientAccountId, String status, int page, int size, String sort);
    default LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(
            String tenantId, String workspaceId, String clientAccountId, String status, int page, int size,
            String sort, Set<UUID> grantedWarehouseIds) {
        return new LogisticsOperationsModels.Page<>(List.of(), page, size, 0);
    }
    /** Sales read limited to the active accounts resolved for the caller's assigned membership. */
    default LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> listForSalesOwner(
            String tenantId, String workspaceId, String status, int page, int size, String sort,
            DispatchSalesOwnerScope scope) {
        return new LogisticsOperationsModels.Page<>(List.of(), page, size, 0);
    }
    LogisticsOperationsModels.DispatchView detail(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    default LogisticsOperationsModels.DispatchView detail(String tenantId, String workspaceId,
                                                           String clientAccountId, String dispatchId,
                                                           Set<UUID> grantedWarehouseIds) {
        throw new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true);
    }
    default LogisticsOperationsModels.DispatchView detailForSalesOwner(
            String tenantId, String workspaceId, String dispatchId, DispatchSalesOwnerScope scope) {
        throw new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true);
    }
    java.util.List<LogisticsOperationsModels.DispatchEventView> events(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    default java.util.List<LogisticsOperationsModels.DispatchEventView> events(
            String tenantId, String workspaceId, String clientAccountId, String dispatchId,
            Set<UUID> grantedWarehouseIds) {
        throw new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true);
    }
    default java.util.List<LogisticsOperationsModels.DispatchEventView> eventsForSalesOwner(
            String tenantId, String workspaceId, String dispatchId, DispatchSalesOwnerScope scope) {
        throw new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true);
    }
    LogisticsOperationsModels.DashboardView dashboard(String tenantId, String workspaceId);
    default LogisticsOperationsModels.DashboardView dashboard(String tenantId, String workspaceId,
                                                               Set<UUID> grantedWarehouseIds) {
        return new LogisticsOperationsModels.DashboardView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    LogisticsOperationsModels.AnalyticsView analytics(String tenantId, String workspaceId, Instant from, Instant to);
    default LogisticsOperationsModels.AnalyticsView analytics(String tenantId, String workspaceId,
                                                               Instant from, Instant to,
                                                               Set<UUID> grantedWarehouseIds) {
        return new LogisticsOperationsModels.AnalyticsView(from, to, 0, 0, 0, 0, 0, 0d, 0d, 0d);
    }
    LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(String tenantId, String workspaceId, String status, int page, int size);
    default LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(
            String tenantId, String workspaceId, String status, int page, int size,
            Set<UUID> grantedWarehouseIds) {
        return new LogisticsOperationsModels.Page<>(List.of(), page, size, 0);
    }
    LogisticsOperationsModels.DispatchDeliveriesView dispatchDeliveries(String tenantId, String workspaceId,
                                                                         String dispatchOrderId);
    default LogisticsOperationsModels.DispatchDeliveriesView dispatchDeliveries(
            String tenantId, String workspaceId, String dispatchOrderId, Set<UUID> grantedWarehouseIds) {
        throw new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true);
    }
    default List<LogisticsOperationsModels.AssigneeView> assignees(String tenantId, String workspaceId) { return List.of(); }
}
