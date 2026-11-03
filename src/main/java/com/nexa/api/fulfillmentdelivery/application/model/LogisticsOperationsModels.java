package com.nexa.api.fulfillmentdelivery.application.model;

import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport-neutral operational views and command values for Logistics operations. */
public final class LogisticsOperationsModels {
    private LogisticsOperationsModels() { }

    @NamedInterface("fulfillment-public")
    public record DispatchView(String id, String dispatchNumber, String reservationId, String salesOrderId,
                               String salesOrderNumber, String clientAccountId, String clientCode,
                               String clientName, String status, String destination, String deliveryArea,
                               String priority, Instant deliveryWindowStart, Instant deliveryWindowEnd,
                               Instant eta, AssignmentView assignment, BigDecimal temperatureMin,
                               BigDecimal temperatureMax, String temperatureUnit, String temperatureStatus,
                               String podId, String podStatus, long version, Instant updatedAt,
                               List<String> alerts, DeliveryAttemptView lastAttempt,
                               String continuationDeliveryId, String continuationDeliveryStatus,
                               List<ObligationLineView> remainingObligation) {
        public DispatchView {
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
            remainingObligation = remainingObligation == null ? List.of() : List.copyOf(remainingObligation);
        }
        public DispatchView buyerSafe() {
            return new DispatchView(id, dispatchNumber, null, null, salesOrderNumber, null, clientCode,
                    clientName, buyerStatus(status), destination, deliveryArea, priority,
                    deliveryWindowStart, deliveryWindowEnd, eta, null, null, null, null, null,
                    podId, podStatus, version, updatedAt, buyerAlerts(alerts),
                    lastAttempt == null ? null : lastAttempt.buyerSafe(), null,
                    continuationDeliveryStatus, remainingObligation);
        }
        public DispatchView salesSafe() {
            return new DispatchView(id, dispatchNumber, null, null, salesOrderNumber, null, clientCode,
                    clientName, buyerStatus(status), destination, deliveryArea, priority,
                    deliveryWindowStart, deliveryWindowEnd, eta, null, null, null, null, null,
                    podId, podStatus, version, updatedAt, buyerAlerts(alerts),
                    lastAttempt == null ? null : lastAttempt.buyerSafe(), null,
                    continuationDeliveryStatus, remainingObligation);
        }
        public DispatchView warehouseSafe() {
            return new DispatchView(id, dispatchNumber, reservationId, null, salesOrderNumber, null,
                    clientCode, clientName, status, destination, deliveryArea, priority,
                    deliveryWindowStart, deliveryWindowEnd, eta, null, null, null, null, null,
                    podId, podStatus, version, updatedAt, List.of(), lastAttempt,
                    continuationDeliveryId, continuationDeliveryStatus, remainingObligation);
        }
        private static String buyerStatus(String value) {
            return switch (value) {
                case "READY_FOR_OPERATIONS", "PREPARING", "ASSIGNED" -> "PREPARING_DELIVERY";
                case "SCHEDULED", "READY_FOR_ROUTE" -> "DELIVERY_SCHEDULED";
                case "IN_ROUTE" -> "IN_TRANSIT";
                case "INCIDENT" -> "DELIVERY_REVIEW";
                case "REPROGRAMMED" -> "DELIVERY_RESCHEDULED";
                case "PARTIAL" -> "PARTIAL";
                case "DELIVERED" -> "DELIVERED";
                case "CANCELLED" -> "DELIVERY_CANCELLED";
                case "PREPARING_DELIVERY", "DELIVERY_SCHEDULED", "IN_TRANSIT", "DELIVERY_REVIEW",
                     "DELIVERY_RESCHEDULED", "DELIVERY_CANCELLED", "UNKNOWN" -> value;
                default -> "UNKNOWN";
            };
        }
        private static List<String> buyerAlerts(List<String> values) {
            return values.stream()
                    .filter(value -> value.equals("DELIVERY_REVIEW") || value.equals("TEMPERATURE_ALERT")
                            || value.equals("CONTINUATION_REQUIRED"))
                    .map(value -> value.equals("TEMPERATURE_ALERT") ? "DELIVERY_REVIEW" : value)
                    .distinct().toList();
        }
    }

    @NamedInterface("fulfillment-public")
    public record DeliveryLineCommand(String catalogItemId, BigDecimal quantity, String unit) { }
    @NamedInterface("fulfillment-public")
    public record ObligationLineView(String catalogItemId, BigDecimal quantity, String unit) { }
    @NamedInterface("fulfillment-public")
    public record DeliveryAttemptView(String id, int attemptNumber, String status, String failureReason,
                                      Instant occurredAt, List<ObligationLineView> deliveredLines) {
        public DeliveryAttemptView {
            deliveredLines = deliveredLines == null ? List.of() : List.copyOf(deliveredLines);
        }
        public DeliveryAttemptView buyerSafe() {
            String publicStatus = switch (status) {
                case "FAILED", "PARTIAL", "DELIVERY_REVIEW" -> "DELIVERY_REVIEW";
                case "FINAL", "DELIVERED" -> "DELIVERED";
                default -> "DELIVERY_UPDATED";
            };
            return new DeliveryAttemptView(null, attemptNumber, publicStatus, null, occurredAt, deliveredLines);
        }
    }
    @NamedInterface("fulfillment-public")
    public record AssignmentView(String responsibleMembershipId, String responsibleDisplayName,
                                 String vehicleReference, String routeName) { }
    @NamedInterface("fulfillment-public")
    public record AssigneeView(String id, String email, String displayName) { }
    @NamedInterface("fulfillment-public")
    public record DispatchEventView(String id, String type, String fromStatus, String toStatus,
                                    String occurredAt, boolean buyerVisible, String summary) { }
    @NamedInterface("fulfillment-public")
    public record HandoffNoteView(String id, String dispatchOrderId, String note,
                                  String authorMembershipId, Instant occurredAt, long dispatchVersion) { }
    @NamedInterface("fulfillment-public")
    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }
    @NamedInterface("fulfillment-public")
    public record DashboardView(long readyForOperations, long preparing, long assigned, long scheduled,
                                long readyForRoute, long inRoute, long incidents, long deliveredToday,
                                long temperatureAlerts, long podPending, long reservationsReady) { }
    @NamedInterface("fulfillment-public")
    public record AnalyticsView(Instant from, Instant to, long dispatches, long delivered, long incidents,
                                long temperatureExcursions, long podCompleted, double onTimeRate,
                                double averagePreparationMinutes, double averageRouteMinutes) { }
    @NamedInterface("fulfillment-public")
    public record ProofOfDeliveryView(String podId, String dispatchOrderId, String dispatchNumber,
                                      String status, String receiverName, Instant completedAt, String notes,
                                      boolean photoEvidenceDeclared, boolean signatureEvidenceDeclared,
                                      Instant updatedAt) { }

    @NamedInterface("fulfillment-public")
    public record DispatchDeliveriesView(UUID dispatchOrderId,
                                         List<DispatchDeliveryStatusView> deliveries) {
        public DispatchDeliveriesView {
            deliveries = List.copyOf(deliveries == null ? List.of() : deliveries);
        }
    }
    @NamedInterface("fulfillment-public")
    public record DispatchDeliveryStatusView(UUID deliveryId, UUID fulfillmentId,
                                             String deliveryStatus, long deliveryVersion,
                                             Instant deliveryUpdatedAt, DispatchDeliveryPodView pod) { }
    @NamedInterface("fulfillment-public")
    public record DispatchDeliveryPodView(UUID id, UUID deliveryId, String status,
                                          String receiverName, Instant completedAt, Instant sealedAt,
                                          boolean photoEvidenceDeclared,
                                          boolean signatureEvidenceDeclared) { }
}
