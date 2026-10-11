package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;

import java.time.Instant;
import java.util.List;

public interface DispatchQueryPersistencePort {
    LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(String tenantId, String workspaceId, String clientAccountId, String status, int page, int size, String sort);
    LogisticsOperationsModels.DispatchView detail(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    java.util.List<LogisticsOperationsModels.DispatchEventView> events(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    LogisticsOperationsModels.DashboardView dashboard(String tenantId, String workspaceId);
    LogisticsOperationsModels.AnalyticsView analytics(String tenantId, String workspaceId, Instant from, Instant to);
    LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(String tenantId, String workspaceId, String status, int page, int size);
    default List<LogisticsOperationsModels.AssigneeView> assignees(String tenantId, String workspaceId) { return List.of(); }
}
