package com.nexa.api.fulfillmentdelivery.application;

import static com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.*;

import com.nexa.api.fulfillmentdelivery.application.port.DispatchCommandPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchQueryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalHandoffPort;
import com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchSalesOwnerScope;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LogisticsOperationsService implements com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsOperations {
    private final DispatchQueryPersistencePort queries;
    private final DispatchCommandPersistencePort commands;
    private final CustomerAccountQuery accounts;
    private final StartDispatchRouteService startDispatchRoute;
    private final OperationalHandoffPort handoff;
    private final WarehouseObjectAccess warehouseAccess;

    public LogisticsOperationsService(DispatchQueryPersistencePort queries, DispatchCommandPersistencePort commands, CustomerAccountQuery accounts,
                                      StartDispatchRouteService startDispatchRoute) {
        this(queries, commands, accounts, startDispatchRoute, null, null);
    }

    public LogisticsOperationsService(DispatchQueryPersistencePort queries, DispatchCommandPersistencePort commands, CustomerAccountQuery accounts,
                                      StartDispatchRouteService startDispatchRoute,
                                      OperationalHandoffPort handoff) {
        this(queries, commands, accounts, startDispatchRoute, handoff, null);
    }

    public LogisticsOperationsService(DispatchQueryPersistencePort queries, DispatchCommandPersistencePort commands,
                                      CustomerAccountQuery accounts, StartDispatchRouteService startDispatchRoute,
                                      OperationalHandoffPort handoff, WarehouseObjectAccess warehouseAccess) {
        this.queries = queries; this.commands = commands; this.accounts = accounts; this.startDispatchRoute = startDispatchRoute;
        this.handoff = handoff; this.warehouseAccess = warehouseAccess;
    }

    @Transactional public Page<DispatchView> list(CurrentAccessContext context, String status, int page, int size, String sort) {
        if (salesOwnerScoped(context)) {
            Page<DispatchView> value = queries.listForSalesOwner(tenant(context), workspace(context), status,
                    page, size, sort, salesOwnerScope(context));
            return new Page<>(value.items().stream().map(item -> safe(context, item)).toList(),
                    value.page(), value.size(), value.total());
        }
        String client = readScope(context);
        Set<UUID> grants = warehouseGrants(context);
        Page<DispatchView> value = grants == null
                ? queries.list(tenant(context), workspace(context), client, status, page, size, sort)
                : queries.list(tenant(context), workspace(context), client, status, page, size, sort, grants);
        return new Page<>(value.items().stream().map(item -> safe(context, item)).toList(), value.page(), value.size(), value.total());
    }
    @Transactional public DispatchView detail(CurrentAccessContext context, String id) {
        if (salesOwnerScoped(context)) {
            return safe(context, queries.detailForSalesOwner(tenant(context), workspace(context), id,
                    salesOwnerScope(context)));
        }
        String client = readScope(context);
        Set<UUID> grants = warehouseGrants(context);
        DispatchView value = grants == null ? queries.detail(tenant(context), workspace(context), client, id)
                : queries.detail(tenant(context), workspace(context), client, id, grants);
        return safe(context, value);
    }
    @Transactional public List<DispatchEventView> events(CurrentAccessContext context, String id) {
        if (salesOwnerScoped(context)) {
            return queries.eventsForSalesOwner(tenant(context), workspace(context), id,
                    salesOwnerScope(context)).stream().map(event -> safeEvent(context, event)).toList();
        }
        String client = readScope(context);
        Set<UUID> grants = warehouseGrants(context);
        List<DispatchEventView> values = grants == null
                ? queries.events(tenant(context), workspace(context), client, id)
                : queries.events(tenant(context), workspace(context), client, id, grants);
        return values.stream().map(event -> safeEvent(context, event)).toList();
    }
    public List<HandoffNoteView> handoffNotes(CurrentAccessContext context, String id) {
        handoffRead(context);
        Set<UUID> grants = warehouseGrants(context);
        if (grants == null) return requireHandoff().notes(tenant(context), workspace(context), null, id);
        return requireHandoff().notes(tenant(context), workspace(context), null, id, grants);
    }

    @Transactional public DispatchView create(CurrentAccessContext c, String reservationId, long version, String key) { write(c); requireKey(key); return commands.create(tenant(c), workspace(c), reservationId, version, actor(c), key, now()); }
    @Transactional public DispatchView prepare(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return commands.prepare(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView assign(CurrentAccessContext c, String id, long version, String key, String membership, String vehicle, String route) { write(c); requireKey(key); return commands.assign(tenant(c), workspace(c), id, version, actor(c), key, membership, vehicle, route, now()); }
    @Transactional public DispatchView schedule(CurrentAccessContext c, String id, long version, String key, Instant start, Instant end, Instant eta) { write(c); requireKey(key); validateWindow(start, end, eta); return commands.schedule(tenant(c), workspace(c), id, version, actor(c), key, start, end, eta, now()); }
    @Transactional public DispatchView ready(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return commands.ready(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView startRoute(CurrentAccessContext c, String id, long version, String key) { write(c); requireKey(key); return startDispatchRoute.execute(tenant(c), workspace(c), id, version, actor(c), key, now()); }
    @Transactional public DispatchView temperature(CurrentAccessContext c, String id, long version, String key, BigDecimal value, String unit, Instant recordedAt, String source) { write(c); requireKey(key); return commands.temperature(tenant(c), workspace(c), id, version, actor(c), key, value, unit, recordedAt == null ? Instant.now() : recordedAt, source, now()); }
    @Transactional public DispatchView incident(CurrentAccessContext c, String id, long version, String key, String type, String severity, boolean buyerVisible, String description, Instant occurredAt, String resolution) { write(c); requireKey(key); return commands.incident(tenant(c), workspace(c), id, version, actor(c), key, type, severity, buyerVisible, description, occurredAt == null ? Instant.now() : occurredAt, resolution, now()); }
    @Transactional public DispatchView reprogram(CurrentAccessContext c, String id, long version, String key, Instant start, Instant end, Instant eta, String reason) { write(c); requireKey(key); validateWindow(start, end, eta); return commands.reprogram(tenant(c), workspace(c), id, version, actor(c), key, start, end, eta, reason, now()); }
    @Transactional public DispatchView cancel(CurrentAccessContext c, String id, long version, String key, String reason) { write(c); requireKey(key); return commands.cancel(tenant(c), workspace(c), id, version, actor(c), key, reason, now()); }
    @Transactional public DispatchView failedAttempt(CurrentAccessContext c, String id, long version, String key, String failureReason, Instant occurredAt) { write(c); requireKey(key); if (failureReason == null || failureReason.isBlank() || failureReason.trim().length() > 2000) throw error("INVALID_REQUEST", false); return commands.failedAttempt(tenant(c), workspace(c), id, version, actor(c), key, failureReason, occurredAt, now()); }
    @Transactional public DispatchView partial(CurrentAccessContext c, String id, long version, String key, List<DeliveryLineCommand> deliveredLines, Instant completedAt, String notes) { write(c); requireKey(key); if (deliveredLines == null || deliveredLines.isEmpty() || notes != null && notes.trim().length() > 2000) throw error("INVALID_REQUEST", false); return commands.partial(tenant(c), workspace(c), id, version, actor(c), key, deliveredLines, completedAt, notes, now()); }
    @Transactional public DispatchView complete(CurrentAccessContext c, String id, long version, String key, String receiver, Instant completedAt, String notes, boolean photo, boolean signature) { write(c); requireKey(key); return commands.complete(tenant(c), workspace(c), id, version, actor(c), key, receiver, completedAt, notes, photo, signature, now()); }
    @Transactional public HandoffNoteView appendHandoffNote(CurrentAccessContext c, String id, long version, String key, String note) {
        Set<UUID> handoffWarehouseGrants = null;
        if (c.allows(Permission.WAREHOUSE_WRITE)) {
            c.requirePermission(Permission.WAREHOUSE_WRITE);
            handoffWarehouseGrants = requiredWarehouseGrants(c);
        } else if (c.allows(Permission.LOGISTICS_WRITE)) {
            c.requirePermission(Permission.LOGISTICS_WRITE);
        } else {
            throw new AccessPolicyViolation("Operational handoff write access is not available");
        }
        requireKey(key);
        if (note == null || note.isBlank()) throw error("INVALID_REQUEST", false);
        return requireHandoff().append(tenant(c), workspace(c), id, version, actor(c), key, note,
                handoffWarehouseGrants, now());
    }

    public DashboardView dashboard(CurrentAccessContext c) {
        logisticsRead(c);
        Set<UUID> grants = warehouseGrants(c);
        return grants == null ? queries.dashboard(tenant(c), workspace(c))
                : queries.dashboard(tenant(c), workspace(c), grants);
    }
    public AnalyticsView analytics(CurrentAccessContext c, Instant from, Instant to) {
        logisticsRead(c);
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(30, ChronoUnit.DAYS) : from;
        if (!start.isBefore(end) || start.plus(366, ChronoUnit.DAYS).isBefore(end)) throw error("INVALID_REQUEST", false);
        Set<UUID> grants = warehouseGrants(c);
        return grants == null ? queries.analytics(tenant(c), workspace(c), start, end)
                : queries.analytics(tenant(c), workspace(c), start, end, grants);
    }
    public Page<ProofOfDeliveryView> proofOfDelivery(CurrentAccessContext c, String status, int page, int size) {
        logisticsRead(c);
        Set<UUID> grants = warehouseGrants(c);
        return grants == null ? queries.proofOfDelivery(tenant(c), workspace(c), status, page, size)
                : queries.proofOfDelivery(tenant(c), workspace(c), status, page, size, grants);
    }
    public DispatchDeliveriesView dispatchDeliveries(CurrentAccessContext c, String dispatchOrderId) {
        logisticsRead(c);
        Set<UUID> grants = warehouseGrants(c);
        return grants == null ? queries.dispatchDeliveries(tenant(c), workspace(c), dispatchOrderId)
                : queries.dispatchDeliveries(tenant(c), workspace(c), dispatchOrderId, grants);
    }
    public List<AssigneeView> assignees(CurrentAccessContext c) { logisticsRead(c); return queries.assignees(tenant(c), workspace(c)); }

    private String readScope(CurrentAccessContext c) {
        if (c.hasRole(MembershipRole.BUYER)) { c.requirePermission(Permission.TRACKING_BUYER_READ); return accounts.findBuyerReference(tenant(c), workspace(c), actor(c)).map(CustomerAccountReference::id).orElseThrow(() -> error("RESOURCE_NOT_FOUND", true)); }
        /* Company Owner is a governance/commercial role. Sales order read
         * capability must not widen into the operational dispatch board; only
         * an explicit logistics/fulfillment grant (fixed or custom) can do so. */
        boolean operationalRead = c.allows(PermissionKey.LOGISTICS_READ)
                || c.allows(PermissionKey.DISPATCH_READ)
                || c.allows(PermissionKey.FULFILLMENT_READ);
        if (c.hasRole(MembershipRole.COMPANY_OWNER) && !operationalRead) {
            throw new AccessPolicyViolation("Company Owner does not have operational logistics access");
        }
        if (c.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
                || c.allows(PermissionKey.LOGISTICS_READ) || c.allows(PermissionKey.DISPATCH_READ)
                || c.allows(Permission.FULFILLMENT_READ) || c.allows(PermissionKey.SALES_ORDER_READ)
                || c.allows(Permission.FULFILLMENT_READ) || c.allows(Permission.LOGISTICS_READ)) return null;
        throw new AccessPolicyViolation("Logistics access is not available");
    }
    private Set<UUID> warehouseGrants(CurrentAccessContext context) {
        if (context.hasRole(MembershipRole.BUYER)
                || context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
                || context.allows(PermissionKey.LOGISTICS_READ)
                || context.allows(PermissionKey.DISPATCH_READ)
                || context.allows(Permission.LOGISTICS_READ)) return null;
        if (warehouseAccess == null) return Set.of();
        Set<UUID> grants = warehouseAccess.activeWarehouseIds(context);
        return grants == null ? Set.of() : Set.copyOf(grants);
    }
    private boolean salesOwnerScoped(CurrentAccessContext context) {
        return context.allows(PermissionKey.SALES_ORDER_READ)
                && !context.hasRole(MembershipRole.COMPANY_OWNER)
                && !context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
                && !context.allows(PermissionKey.LOGISTICS_READ)
                && !context.allows(PermissionKey.DISPATCH_READ)
                && !context.allows(PermissionKey.FULFILLMENT_READ)
                && !context.allows(Permission.LOGISTICS_READ)
                && !context.allows(Permission.FULFILLMENT_READ);
    }
    private DispatchSalesOwnerScope salesOwnerScope(CurrentAccessContext context) {
        Set<UUID> accountIds = accounts.lockActiveSalesOwnerAccountIds(tenant(context), workspace(context), actor(context))
                .stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new DispatchSalesOwnerScope(accountIds);
    }
    private Set<UUID> requiredWarehouseGrants(CurrentAccessContext context) {
        Set<UUID> grants = warehouseGrants(context);
        return grants == null ? Set.of() : grants;
    }
    private static void logisticsRead(CurrentAccessContext c) { c.requirePermission(Permission.LOGISTICS_READ); }
    private static void write(CurrentAccessContext c) { c.requirePermission(Permission.LOGISTICS_WRITE); }
    private static void handoffRead(CurrentAccessContext c) {
        if (c.allows(Permission.FULFILLMENT_READ)) c.requirePermission(Permission.FULFILLMENT_READ);
        else if (c.allows(Permission.LOGISTICS_READ)) c.requirePermission(Permission.LOGISTICS_READ);
        else throw new AccessPolicyViolation("Operational handoff access is not available");
    }
    private OperationalHandoffPort requireHandoff() {
        if (handoff == null) throw error("HANDOFF_UNAVAILABLE", false);
        return handoff;
    }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false); }
    private static void validateWindow(Instant start, Instant end, Instant eta) { if (start == null || end == null || !end.isAfter(start) || start.isBefore(Instant.now().minus(5, ChronoUnit.MINUTES)) || start.isAfter(Instant.now().plus(366, ChronoUnit.DAYS))) throw error("INVALID_REQUEST", false); if (eta != null && (eta.isBefore(start) || eta.isAfter(end))) throw error("INVALID_REQUEST", false); }
    private static String tenant(CurrentAccessContext c) { return c.tenantId().toString(); } private static String workspace(CurrentAccessContext c) { return c.workspaceId().toString(); } private static String actor(CurrentAccessContext c) { return c.membershipId().toString(); } private static long now() { return System.currentTimeMillis(); }
    private static LogisticsException error(String code, boolean notFound) { return new LogisticsException(code, notFound); }

    private static DispatchView safe(CurrentAccessContext context, DispatchView value) {
        if (context.hasRole(MembershipRole.BUYER)) return value.buyerSafe();
        if (context.hasRole(MembershipRole.SALES) || (context.allows(PermissionKey.SALES_ORDER_READ) && !context.allows(Permission.LOGISTICS_READ) && !context.allows(Permission.FULFILLMENT_READ))) return value.salesSafe();
        if ((context.hasRole(MembershipRole.WAREHOUSE) || context.allows(Permission.FULFILLMENT_READ)) && !context.allows(Permission.LOGISTICS_READ)) return value.warehouseSafe();
        return value;
    }
    private static DispatchEventView safeEvent(CurrentAccessContext context, DispatchEventView value) {
        if (context.hasRole(MembershipRole.BUYER) || context.hasRole(MembershipRole.SALES) || (context.allows(PermissionKey.SALES_ORDER_READ) && !context.allows(Permission.LOGISTICS_READ) && !context.allows(Permission.FULFILLMENT_READ))) {
            String publicType = switch (value.type()) {
                case "logistics.dispatch.scheduled", "logistics.dispatch.reprogrammed", "DELIVERY_SCHEDULED" -> "DELIVERY_SCHEDULED";
                case "logistics.dispatch.route-started", "IN_TRANSIT" -> "IN_TRANSIT";
                case "logistics.dispatch.delivered", "logistics.pod.completed", "DELIVERED" -> "DELIVERED";
                case "logistics.dispatch.cancelled", "DELIVERY_CANCELLED" -> "DELIVERY_CANCELLED";
                case "logistics.dispatch.incident-recorded", "logistics.dispatch.buyer-temperature-review", "logistics.delivery.attempt-failed", "DELIVERY_REVIEW" -> "DELIVERY_REVIEW";
                case "logistics.delivery.partially-completed" -> "PARTIAL";
                case "logistics.delivery.continuation-created" -> "CONTINUATION_REQUIRED";
                default -> "DELIVERY_UPDATED";
            };
            return new DispatchEventView(value.id(), publicType, null, null, value.occurredAt(), true, publicType);
        }
        return value;
    }
    public static final class LogisticsException extends com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsOperationException {
        public LogisticsException(String code, boolean notFound) { super(code, notFound); }
    }
}
