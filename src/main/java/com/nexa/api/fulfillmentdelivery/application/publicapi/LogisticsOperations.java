package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Scoped operational actions and queries for Logistics and dispatch endpoints. */
public interface LogisticsOperations {
    LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(
            CurrentAccessContext context, String status, int page, int size, String sort);
    LogisticsOperationsModels.DispatchView detail(CurrentAccessContext context, String dispatchId);
    List<LogisticsOperationsModels.DispatchEventView> events(CurrentAccessContext context, String dispatchId);
    List<LogisticsOperationsModels.HandoffNoteView> handoffNotes(CurrentAccessContext context, String dispatchId);
    LogisticsOperationsModels.DispatchView create(CurrentAccessContext context, String reservationId,
                                                   long version, String idempotencyKey);
    LogisticsOperationsModels.DispatchView prepare(CurrentAccessContext context, String dispatchId,
                                                    long version, String idempotencyKey);
    LogisticsOperationsModels.DispatchView assign(CurrentAccessContext context, String dispatchId,
                                                   long version, String idempotencyKey, String membership,
                                                   String vehicle, String route);
    LogisticsOperationsModels.DispatchView schedule(CurrentAccessContext context, String dispatchId,
                                                     long version, String idempotencyKey, Instant start,
                                                     Instant end, Instant eta);
    LogisticsOperationsModels.DispatchView ready(CurrentAccessContext context, String dispatchId,
                                                  long version, String idempotencyKey);
    LogisticsOperationsModels.DispatchView startRoute(CurrentAccessContext context, String dispatchId,
                                                       long version, String idempotencyKey);
    LogisticsOperationsModels.DispatchView temperature(CurrentAccessContext context, String dispatchId,
                                                        long version, String idempotencyKey, BigDecimal value,
                                                        String unit, Instant recordedAt, String source);
    LogisticsOperationsModels.DispatchView incident(CurrentAccessContext context, String dispatchId,
                                                     long version, String idempotencyKey, String type,
                                                     String severity, boolean buyerVisible, String description,
                                                     Instant occurredAt, String resolution);
    LogisticsOperationsModels.DispatchView reprogram(CurrentAccessContext context, String dispatchId,
                                                      long version, String idempotencyKey, Instant start,
                                                      Instant end, Instant eta, String reason);
    LogisticsOperationsModels.DispatchView cancel(CurrentAccessContext context, String dispatchId,
                                                   long version, String idempotencyKey, String reason);
    LogisticsOperationsModels.DispatchView failedAttempt(CurrentAccessContext context, String dispatchId,
                                                          long version, String idempotencyKey,
                                                          String failureReason, Instant occurredAt);
    LogisticsOperationsModels.DispatchView partial(CurrentAccessContext context, String dispatchId,
                                                    long version, String idempotencyKey,
                                                    List<LogisticsOperationsModels.DeliveryLineCommand> lines,
                                                    Instant completedAt, String notes);
    LogisticsOperationsModels.DispatchView complete(CurrentAccessContext context, String dispatchId,
                                                     long version, String idempotencyKey, String receiver,
                                                     Instant completedAt, String notes, boolean photo,
                                                     boolean signature);
    LogisticsOperationsModels.HandoffNoteView appendHandoffNote(CurrentAccessContext context,
                                                                 String dispatchId, long version,
                                                                 String idempotencyKey, String note);
    LogisticsOperationsModels.DashboardView dashboard(CurrentAccessContext context);
    LogisticsOperationsModels.AnalyticsView analytics(CurrentAccessContext context, Instant from, Instant to);
    LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(
            CurrentAccessContext context, String status, int page, int size);
    List<LogisticsOperationsModels.AssigneeView> assignees(CurrentAccessContext context);
}
