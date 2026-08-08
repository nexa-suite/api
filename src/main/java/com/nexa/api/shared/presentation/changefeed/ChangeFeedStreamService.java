package com.nexa.api.shared.presentation.changefeed;

import com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.shared.application.changefeed.ChangeEventAudience;
import com.nexa.api.shared.application.changefeed.ChangeEventView;
import com.nexa.api.shared.application.changefeed.ChangeFeedCapacityException;
import com.nexa.api.shared.application.changefeed.ChangeFeedConnectionRegistry;
import com.nexa.api.shared.application.changefeed.ChangeFeedQueryPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Web adapter for the authenticated change-feed stream; application ports remain transport-neutral. */
public final class ChangeFeedStreamService implements AutoCloseable {
    private static final int MAX_REPLAY = 100;
    private static final long MAX_STREAM_MILLIS = 60_000;
    private final ChangeFeedQueryPort feed;
    private final ResolveCurrentAccessContextUseCase accessContext;
    private final ValidateAccessSessionUseCase accessSession;
    private final ClientAccountPersistencePort accounts;
    private final ChangeFeedConnectionRegistry connections;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "nexa-change-feed"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger activeStreams = new AtomicInteger();

    public ChangeFeedStreamService(ChangeFeedQueryPort feed, ResolveCurrentAccessContextUseCase accessContext,
            ValidateAccessSessionUseCase accessSession, ClientAccountPersistencePort accounts,
            ChangeFeedConnectionRegistry connections) {
        this.feed = feed; this.accessContext = accessContext; this.accessSession = accessSession; this.accounts = accounts; this.connections = connections;
    }

    public SseEmitter open(CurrentAccessContext initial, Jwt jwt, String lastEventId) {
        long last = parseLastEventId(lastEventId);
        CurrentAccessContext verified = verify(jwt, initial);
        ChangeFeedConnectionRegistry.Lease lease = connections.reserve(required(jwt, "sid"),
                verified.userId().toString() + ":" + verified.surface(), verified.tenantId() + ":" + verified.workspaceId());
        if (activeStreams.incrementAndGet() > 100) { activeStreams.decrementAndGet(); lease.close(); throw new ChangeFeedCapacityException(); }
        SseEmitter emitter = new SseEmitter(MAX_STREAM_MILLIS + 5_000);
        AtomicBoolean closed = new AtomicBoolean(); AtomicLong cursor = new AtomicLong(last); long started = System.currentTimeMillis();
        Runnable release = () -> { if (closed.compareAndSet(false, true)) { activeStreams.decrementAndGet(); lease.close(); } };
        emitter.onCompletion(release); emitter.onTimeout(() -> { release.run(); emitter.complete(); }); emitter.onError(ignored -> release.run());
        Set<ChangeEventAudience> audiences = audiences(initial);
        try {
            verified = verify(jwt, initial);
            String clientAccount = clientAccount(verified);
            if (isTooOld(verified, clientAccount, last)) { sendResync(emitter); release.run(); emitter.complete(); return emitter; }
            sendBatch(emitter, cursor, feed.after(scope(verified), workspace(verified), clientAccount, audiences, last, MAX_REPLAY), jwt, initial);
        } catch (RuntimeException | IOException exception) {
            release.run(); emitter.completeWithError(exception); return emitter;
        }
        scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            if (System.currentTimeMillis() - started >= MAX_STREAM_MILLIS) { release.run(); emitter.complete(); return; }
            try {
                CurrentAccessContext current = verify(jwt, initial); String clientAccount = clientAccount(current); long position = cursor.get();
                Set<ChangeEventAudience> currentAudiences = audiences(current);
                if (isTooOld(current, clientAccount, position)) { sendResync(emitter); release.run(); emitter.complete(); return; }
                sendBatch(emitter, cursor, feed.after(scope(current), workspace(current), clientAccount, currentAudiences, position, MAX_REPLAY), jwt, initial);
                emitter.send(SseEmitter.event().name("heartbeat").data("{}", MediaType.APPLICATION_JSON));
            } catch (RuntimeException | IOException exception) { release.run(); emitter.completeWithError(exception); }
        }, 15, 15, TimeUnit.SECONDS);
        return emitter;
    }

    private void sendBatch(SseEmitter emitter, AtomicLong cursor, List<ChangeEventView> events, Jwt jwt, CurrentAccessContext expected) throws IOException {
        for (ChangeEventView event : events) {
            verify(jwt, expected);
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.eventType()).data(event.dataJson(), MediaType.APPLICATION_JSON));
            cursor.set(event.sequence());
        }
    }
    private void sendResync(SseEmitter emitter) throws IOException { emitter.send(SseEmitter.event().name("resync-required").data("{\"reason\":\"replay-window-expired\"}", MediaType.APPLICATION_JSON)); }
    private boolean isTooOld(CurrentAccessContext context, String clientAccount, long last) { long minimum = feed.minimumId(scope(context), workspace(context), clientAccount); return last > 0 && minimum > 0 && last < minimum - 1; }
    private static Set<ChangeEventAudience> audiences(CurrentAccessContext context) {
        EnumSet<ChangeEventAudience> result = EnumSet.noneOf(ChangeEventAudience.class);
        for (String role : context.roleCodes()) switch (role.toUpperCase(Locale.ROOT)) {
            case "TENANT_ADMIN", "COMPANY_OWNER" -> result.add(ChangeEventAudience.OWNER);
            case "SALES" -> result.add(ChangeEventAudience.SALES);
            case "WAREHOUSE" -> result.add(ChangeEventAudience.WAREHOUSE);
            case "LOGISTICS" -> result.add(ChangeEventAudience.LOGISTICS);
            case "BUYER" -> result.add(ChangeEventAudience.BUYER);
            default -> { }
        }
        if (!result.contains(ChangeEventAudience.OWNER)) {
            if (allowsAny(context, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.SALES_DASHBOARD_READ, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.SALES_PURCHASE_REQUEST_READ, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.SALES_ORDER_READ, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.CLIENT_READ)) result.add(ChangeEventAudience.SALES);
            if (allowsAny(context, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.WAREHOUSE_READ, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.INVENTORY_READ)) result.add(ChangeEventAudience.WAREHOUSE);
            if (allowsAny(context, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.LOGISTICS_READ, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.DISPATCH_READ)) result.add(ChangeEventAudience.LOGISTICS);
            if (context.allows(com.nexa.api.tenantmanagement.domain.model.access.PermissionKey.BUYER_ORDER_READ)) result.add(ChangeEventAudience.BUYER);
        }
        return Set.copyOf(result);
    }
    private static boolean allowsAny(CurrentAccessContext context, com.nexa.api.tenantmanagement.domain.model.access.PermissionKey... permissions) { return java.util.Arrays.stream(permissions).anyMatch(context::allows); }
    private CurrentAccessContext verify(Jwt jwt, CurrentAccessContext expected) {
        Surface surface = Surface.valueOf(required(jwt, "surface").toUpperCase(Locale.ROOT));
        accessSession.validate(new SessionId(required(jwt, "sid")), new UserAccountId(jwt.getSubject()), ClientSurface.valueOf(surface.name()), requiredLong(jwt, "authorization_version"));
        CurrentAccessContext resolved = accessContext.resolve(new CurrentAccessRequest(new UserId(jwt.getSubject()), new TenantId(required(jwt, "tenant_id")), new WorkspaceId(required(jwt, "workspace_id")), surface));
        if (!resolved.membershipId().equals(expected.membershipId()) || !resolved.roleCodes().equals(expected.roleCodes()) || !resolved.roleDefinitionIds().equals(expected.roleDefinitionIds()) || !resolved.tenantId().equals(expected.tenantId()) || !resolved.workspaceId().equals(expected.workspaceId()) || !resolved.surface().equals(expected.surface()) || resolved.authorizationVersion() != requiredLong(jwt, "authorization_version")) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Change feed access context changed");
        return resolved;
    }
    private String clientAccount(CurrentAccessContext context) { return context.hasRole(com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole.BUYER) ? accounts.findForBuyer(scope(context), workspace(context), context.membershipId().toString()).map(ClientAccountView::id).orElseThrow() : null; }
    private static String required(Jwt jwt, String name) { String value = jwt.getClaimAsString(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing JWT claim " + name); return value; }
    private static long requiredLong(Jwt jwt, String name) { Object raw = jwt.getClaims().get(name); try { long value = raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw)); if (value < 0) throw new NumberFormatException(); return value; } catch (RuntimeException exception) { throw new IllegalArgumentException("Missing or invalid JWT claim " + name, exception); } }
    private static long parseLastEventId(String value) { if (value == null || value.isBlank()) return 0; try { long parsed = Long.parseLong(value); if (parsed < 0) throw new NumberFormatException(); return parsed; } catch (NumberFormatException exception) { throw new IllegalArgumentException("Last-Event-ID is invalid"); } }
    private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
    @Override public void close() { scheduler.shutdownNow(); }
}
