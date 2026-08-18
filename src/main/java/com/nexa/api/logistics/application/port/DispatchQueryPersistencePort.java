package com.nexa.api.logistics.application.port;

import com.nexa.api.logistics.application.LogisticsOperationsService;

import java.time.Instant;
import java.util.List;

public interface DispatchQueryPersistencePort {
    LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> list(String tenantId, String workspaceId, String clientAccountId, String status, int page, int size, String sort);
    LogisticsOperationsService.DispatchView detail(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    java.util.List<LogisticsOperationsService.DispatchEventView> events(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    LogisticsOperationsService.DashboardView dashboard(String tenantId, String workspaceId);
    LogisticsOperationsService.AnalyticsView analytics(String tenantId, String workspaceId, Instant from, Instant to);
    LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> proofOfDelivery(String tenantId, String workspaceId, String status, int page, int size);
    default List<LogisticsOperationsService.AssigneeView> assignees(String tenantId, String workspaceId) { return List.of(); }
}
