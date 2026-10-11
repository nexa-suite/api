package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface DispatchCommandPersistencePort {
    LogisticsOperationsModels.DispatchView create(String tenantId, String workspaceId, String reservationId, long reservationVersion, String actorMembershipId, String key, long now);
    LogisticsOperationsModels.DispatchView prepare(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now);
    LogisticsOperationsModels.DispatchView assign(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String responsibleMembershipId, String vehicleReference, String routeName, long now);
    LogisticsOperationsModels.DispatchView schedule(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, long now);
    LogisticsOperationsModels.DispatchView ready(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now);
    LogisticsOperationsModels.DispatchView temperature(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, BigDecimal value, String unit, Instant recordedAt, String source, long now);
    LogisticsOperationsModels.DispatchView incident(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String type, String severity, boolean buyerVisible, String description, Instant occurredAt, String resolution, long now);
    LogisticsOperationsModels.DispatchView reprogram(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, String reason, long now);
    LogisticsOperationsModels.DispatchView cancel(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String reason, long now);
    LogisticsOperationsModels.DispatchView failedAttempt(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String failureReason, Instant occurredAt, long now);
    LogisticsOperationsModels.DispatchView partial(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, List<LogisticsOperationsModels.DeliveryLineCommand> deliveredLines, Instant completedAt, String notes, long now);
    LogisticsOperationsModels.DispatchView complete(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String receiverName, Instant completedAt, String notes, boolean photoDeclared, boolean signatureDeclared, long now);
}
