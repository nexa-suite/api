package com.nexa.api.logistics.application.port;

import com.nexa.api.logistics.application.LogisticsOperationsService;

import java.time.Instant;

/** Persistence boundary for the Logistics bounded context. No SQL crosses this port. */
public interface LogisticsPersistencePort {
    LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> list(String tenantId, String workspaceId, String clientAccountId, String status, int page, int size, String sort);
    LogisticsOperationsService.DispatchView detail(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    java.util.List<LogisticsOperationsService.DispatchEventView> events(String tenantId, String workspaceId, String clientAccountId, String dispatchId);
    LogisticsOperationsService.DispatchView create(String tenantId, String workspaceId, String reservationId, long reservationVersion, String actorMembershipId, String key, long now);
    LogisticsOperationsService.DispatchView prepare(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now);
    LogisticsOperationsService.DispatchView assign(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String responsibleMembershipId, String vehicleReference, String routeName, long now);
    LogisticsOperationsService.DispatchView schedule(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, long now);
    LogisticsOperationsService.DispatchView ready(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now);
    LogisticsOperationsService.DispatchView temperature(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, java.math.BigDecimal value, String unit, Instant recordedAt, String source, long now);
    LogisticsOperationsService.DispatchView incident(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String type, String severity, boolean buyerVisible, String description, Instant occurredAt, String resolution, long now);
    LogisticsOperationsService.DispatchView reprogram(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, String reason, long now);
    LogisticsOperationsService.DispatchView cancel(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String reason, long now);
    LogisticsOperationsService.DispatchView complete(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String receiverName, Instant completedAt, String notes, boolean photoDeclared, boolean signatureDeclared, long now);
    LogisticsOperationsService.DashboardView dashboard(String tenantId, String workspaceId);
    LogisticsOperationsService.AnalyticsView analytics(String tenantId, String workspaceId, Instant from, Instant to);
    LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> proofOfDelivery(String tenantId, String workspaceId, String status, int page, int size);
}
