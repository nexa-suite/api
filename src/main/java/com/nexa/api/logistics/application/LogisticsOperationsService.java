package com.nexa.api.logistics.application;

import com.nexa.api.logistics.application.port.LogisticsPersistencePort;
import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class LogisticsOperationsService {
    private final LogisticsPersistencePort persistence;
    private final ClientAccountPersistencePort accounts;

    public LogisticsOperationsService(LogisticsPersistencePort persistence, ClientAccountPersistencePort accounts) {
        this.persistence = persistence; this.accounts = accounts;
    }

    public Page<DispatchView> list(CurrentAccessContext context, String status, int page, int size, String sort) {
        String client = readScope(context);
        return persistence.list(tenant(context), workspace(context), client, status, page, size, sort);
    }
    public DispatchView detail(CurrentAccessContext context, String id) { return persistence.detail(tenant(context), workspace(context), readScope(context), id); }
    public List<DispatchEventView> events(CurrentAccessContext context, String id) { return persistence.events(tenant(context), workspace(context), readScope(context), id); }

    @Transactional public DispatchView create(CurrentAccessContext c, String reservationId, long version, String key) { write(c); requireKey(key); return persistence.create(tenant(c), workspace(c), reservationId, version, actor(c), key, now()); }
    @Transactional public DispatchView prepare(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return persistence.prepare(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView assign(CurrentAccessContext c, String id, long version, String key, String membership, String vehicle, String route) { write(c); requireKey(key); return persistence.assign(tenant(c), workspace(c), id, version, actor(c), key, membership, vehicle, route, now()); }
    @Transactional public DispatchView schedule(CurrentAccessContext c, String id, long version, String key, Instant start, Instant end, Instant eta) { write(c); requireKey(key); validateWindow(start, end, eta); return persistence.schedule(tenant(c), workspace(c), id, version, actor(c), key, start, end, eta, now()); }
    @Transactional public DispatchView ready(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return persistence.ready(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView startRoute(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return persistence.startRoute(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView temperature(CurrentAccessContext c, String id, long version, String key, BigDecimal value, String unit, Instant recordedAt, String source) { write(c); requireKey(key); return persistence.temperature(tenant(c), workspace(c), id, version, actor(c), key, value, unit, recordedAt == null ? Instant.now() : recordedAt, source, now()); }
    @Transactional public DispatchView incident(CurrentAccessContext c, String id, long version, String key, String type, String severity, boolean buyerVisible, String description, Instant occurredAt, String resolution) { write(c); requireKey(key); return persistence.incident(tenant(c), workspace(c), id, version, actor(c), key, type, severity, buyerVisible, description, occurredAt == null ? Instant.now() : occurredAt, resolution, now()); }
    @Transactional public DispatchView reprogram(CurrentAccessContext c, String id, long version, String key, Instant start, Instant end, Instant eta, String reason) { write(c); requireKey(key); validateWindow(start, end, eta); return persistence.reprogram(tenant(c), workspace(c), id, version, actor(c), key, start, end, eta, reason, now()); }
    @Transactional public DispatchView cancel(CurrentAccessContext c, String id, long version, String key, String reason) { write(c); requireKey(key); return persistence.cancel(tenant(c), workspace(c), id, version, actor(c), key, reason, now()); }
    @Transactional public DispatchView complete(CurrentAccessContext c, String id, long version, String key, String receiver, Instant completedAt, String notes, boolean photo, boolean signature) { write(c); requireKey(key); return persistence.complete(tenant(c), workspace(c), id, version, actor(c), key, receiver, completedAt == null ? Instant.now() : completedAt, notes, photo, signature, now()); }

    public DashboardView dashboard(CurrentAccessContext c) { logisticsRead(c); return persistence.dashboard(tenant(c), workspace(c)); }
    public AnalyticsView analytics(CurrentAccessContext c, Instant from, Instant to) { logisticsRead(c); Instant end = to == null ? Instant.now() : to; Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from; if (!start.isBefore(end) || start.plus(366, ChronoUnit.DAYS).isBefore(end)) throw error("INVALID_REQUEST", false); return persistence.analytics(tenant(c), workspace(c), start, end); }
    public Page<ProofOfDeliveryView> proofOfDelivery(CurrentAccessContext c, String status, int page, int size) { logisticsRead(c); return persistence.proofOfDelivery(tenant(c), workspace(c), status, page, size); }

    private String readScope(CurrentAccessContext c) {
        if (c.role() == MembershipRole.BUYER) { c.requirePermission(Permission.TRACKING_BUYER_READ); return accounts.findForBuyer(tenant(c), workspace(c), actor(c)).map(ClientAccountView::id).orElseThrow(() -> error("RESOURCE_NOT_FOUND", true)); }
        if (c.role() == MembershipRole.SALES) c.requirePermission(Permission.SALES_READ);
        else if (c.role() == MembershipRole.WAREHOUSE) c.requirePermission(Permission.FULFILLMENT_READ);
        else if (c.role() == MembershipRole.LOGISTICS) c.requirePermission(Permission.LOGISTICS_READ);
        else throw new AccessPolicyViolation("Logistics access is not available");
        return null;
    }
    private static void logisticsRead(CurrentAccessContext c) { if (c.role() != MembershipRole.LOGISTICS) throw new AccessPolicyViolation("Logistics access is required"); c.requirePermission(Permission.LOGISTICS_READ); }
    private static void write(CurrentAccessContext c) { if (c.role() != MembershipRole.LOGISTICS) throw new AccessPolicyViolation("Logistics write access is required"); c.requirePermission(Permission.LOGISTICS_WRITE); }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false); }
    private static void validateWindow(Instant start, Instant end, Instant eta) { if (start == null || end == null || !end.isAfter(start) || start.isBefore(Instant.now().minus(5, ChronoUnit.MINUTES)) || start.isAfter(Instant.now().plus(366, ChronoUnit.DAYS))) throw error("INVALID_REQUEST", false); if (eta != null && (eta.isBefore(start) || eta.isAfter(end))) throw error("INVALID_REQUEST", false); }
    private static String tenant(CurrentAccessContext c) { return c.tenantId().toString(); } private static String workspace(CurrentAccessContext c) { return c.workspaceId().toString(); } private static String actor(CurrentAccessContext c) { return c.membershipId().toString(); } private static long now() { return System.currentTimeMillis(); }
    private static LogisticsException error(String code, boolean notFound) { return new LogisticsException(code, notFound); }

    public record DispatchView(String id, String dispatchNumber, String reservationId, String salesOrderId, String salesOrderNumber, String clientAccountId, String status,
                                String destination, Instant deliveryWindowStart, Instant deliveryWindowEnd, Instant eta,
                                AssignmentView assignment, BigDecimal temperatureMin, BigDecimal temperatureMax, String temperatureUnit,
                                String temperatureStatus, String podStatus, long version, Instant updatedAt, List<String> alerts) {
        public DispatchView { alerts = alerts == null ? List.of() : List.copyOf(alerts); }
        public DispatchView buyerSafe() { return new DispatchView(id, dispatchNumber, null, salesOrderId, salesOrderNumber, clientAccountId, buyerStatus(status), destination,
                deliveryWindowStart, deliveryWindowEnd, eta, null, null, null, null, null, podStatus, version, updatedAt, buyerAlerts(alerts)); }
        private static String buyerStatus(String value) { return switch (value) { case "READY_FOR_OPERATIONS", "PREPARING", "ASSIGNED" -> "PREPARING_DELIVERY"; case "SCHEDULED", "READY_FOR_ROUTE" -> "DELIVERY_SCHEDULED"; case "IN_ROUTE" -> "IN_TRANSIT"; case "INCIDENT" -> "DELIVERY_REVIEW"; case "REPROGRAMMED" -> "DELIVERY_RESCHEDULED"; case "DELIVERED" -> "DELIVERED"; case "CANCELLED" -> "DELIVERY_CANCELLED"; default -> "UNKNOWN"; }; }
        private static List<String> buyerAlerts(List<String> values) { return values.stream().filter(value -> value.equals("DELIVERY_REVIEW")).toList(); }
    }
    public record AssignmentView(String responsibleMembershipId, String responsibleDisplayName, String vehicleReference, String routeName) { }
    public record DispatchEventView(String id, String type, String fromStatus, String toStatus, String occurredAt, boolean buyerVisible, String summary) { }
    public record Page<T>(List<T> items, int page, int size, long total) { public Page { items = List.copyOf(items); } }
    public record DashboardView(long readyForOperations, long preparing, long assigned, long scheduled, long readyForRoute, long inRoute, long incidents, long deliveredToday, long temperatureAlerts, long podPending, long reservationsReady) { }
    public record AnalyticsView(Instant from, Instant to, long dispatches, long delivered, long incidents, long temperatureExcursions, long podCompleted, double onTimeRate, double averagePreparationMinutes, double averageRouteMinutes) { }
    public record ProofOfDeliveryView(String dispatchOrderId, String dispatchNumber, String status, String receiverName, Instant completedAt, String notes, boolean photoEvidenceDeclared, boolean signatureEvidenceDeclared, Instant updatedAt) { }
    public static final class LogisticsException extends RuntimeException { private final String code; private final boolean notFound; public LogisticsException(String code, boolean notFound) { super(code); this.code = code; this.notFound = notFound; } public String code() { return code; } public boolean notFound() { return notFound; } }
}
