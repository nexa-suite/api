package com.nexa.api.shared.application.changefeed;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ChangeFeedStreamService implements AutoCloseable {
	private static final int MAX_REPLAY = 100;
	private static final long MAX_STREAM_MILLIS = 60_000;
	private final ChangeFeedQueryPort feed;
	private final ResolveCurrentAccessContextUseCase accessContext;
	private final ValidateAccessSessionUseCase accessSession;
	private final ClientAccountPersistencePort accounts;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "nexa-change-feed"); thread.setDaemon(true); return thread;
	});
	private final AtomicInteger activeStreams = new AtomicInteger();

	public ChangeFeedStreamService(ChangeFeedQueryPort feed, ResolveCurrentAccessContextUseCase accessContext,
			ValidateAccessSessionUseCase accessSession, ClientAccountPersistencePort accounts) {
		this.feed = feed; this.accessContext = accessContext; this.accessSession = accessSession; this.accounts = accounts;
	}

	public SseEmitter open(CurrentAccessContext initial, Jwt jwt, String lastEventId) {
		if (activeStreams.incrementAndGet() > 100) { activeStreams.decrementAndGet(); throw new ChangeFeedCapacityException(); }
		long last = parseLastEventId(lastEventId);
		SseEmitter emitter = new SseEmitter(MAX_STREAM_MILLIS + 5_000);
		AtomicBoolean closed = new AtomicBoolean(); AtomicLong cursor = new AtomicLong(last); long started = System.currentTimeMillis();
		Runnable release = () -> { if (closed.compareAndSet(false, true)) activeStreams.decrementAndGet(); };
		emitter.onCompletion(release); emitter.onTimeout(() -> { release.run(); emitter.complete(); }); emitter.onError(ignored -> release.run());
		try {
			CurrentAccessContext verified = verify(jwt, initial);
			String clientAccount = clientAccount(verified);
			if (isTooOld(verified, clientAccount, last)) { sendResync(emitter); release.run(); emitter.complete(); return emitter; }
			sendBatch(emitter, cursor, feed.after(scope(verified), workspace(verified), clientAccount, last, MAX_REPLAY));
		} catch (RuntimeException | IOException exception) {
			release.run(); emitter.completeWithError(exception); return emitter;
		}
		scheduler.scheduleAtFixedRate(() -> {
			if (closed.get()) return;
			if (System.currentTimeMillis() - started >= MAX_STREAM_MILLIS) { release.run(); emitter.complete(); return; }
			try {
				CurrentAccessContext verified = verify(jwt, initial); String clientAccount = clientAccount(verified); long position = cursor.get();
				if (isTooOld(verified, clientAccount, position)) { sendResync(emitter); release.run(); emitter.complete(); return; }
				sendBatch(emitter, cursor, feed.after(scope(verified), workspace(verified), clientAccount, position, MAX_REPLAY));
				emitter.send(SseEmitter.event().name("heartbeat").data("{}", MediaType.APPLICATION_JSON));
			} catch (RuntimeException | IOException exception) { release.run(); emitter.completeWithError(exception); }
		}, 15, 15, TimeUnit.SECONDS);
		return emitter;
	}

	private void sendBatch(SseEmitter emitter, AtomicLong cursor, List<ChangeEventView> events) throws IOException {
		for (ChangeEventView event : events) {
			emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.eventType()).data(event.payload(), MediaType.APPLICATION_JSON));
			cursor.set(event.id());
		}
	}
	private void sendResync(SseEmitter emitter) throws IOException { emitter.send(SseEmitter.event().name("resync-required").data("{\"reason\":\"replay-window-expired\"}", MediaType.APPLICATION_JSON)); }
	private boolean isTooOld(CurrentAccessContext context, String clientAccount, long last) { long minimum = feed.minimumId(scope(context), workspace(context), clientAccount); return last > 0 && minimum > 0 && last < minimum - 1; }
	private CurrentAccessContext verify(Jwt jwt, CurrentAccessContext expected) {
		Surface surface = Surface.valueOf(required(jwt, "surface").toUpperCase(Locale.ROOT));
		accessSession.validate(new SessionId(required(jwt, "sid")), new UserAccountId(jwt.getSubject()), ClientSurface.valueOf(surface.name()));
		CurrentAccessContext resolved = accessContext.resolve(new CurrentAccessRequest(new UserId(jwt.getSubject()),
				new com.nexa.api.tenantmanagement.domain.model.identity.TenantId(required(jwt, "tenant_id")),
				new com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId(required(jwt, "workspace_id")), surface));
		if (!resolved.membershipId().equals(expected.membershipId()) || !resolved.role().equals(expected.role())) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Change feed access context changed");
		return resolved;
	}
	private String clientAccount(CurrentAccessContext context) { return context.role().name().equals("BUYER") ? accounts.findForBuyer(scope(context), workspace(context), context.membershipId().toString()).map(ClientAccountView::id).orElseThrow() : null; }
	private static String required(Jwt jwt, String name) { String value = jwt.getClaimAsString(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing JWT claim " + name); return value; }
	private static long parseLastEventId(String value) { if (value == null || value.isBlank()) return 0; try { long parsed = Long.parseLong(value); if (parsed < 0) throw new NumberFormatException(); return parsed; } catch (NumberFormatException exception) { throw new IllegalArgumentException("Last-Event-ID is invalid"); } }
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	@Override public void close() { scheduler.shutdownNow(); }
}
