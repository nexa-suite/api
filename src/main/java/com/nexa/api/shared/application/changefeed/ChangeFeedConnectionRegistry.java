package com.nexa.api.shared.application.changefeed;

import java.util.HashMap;
import java.util.Map;

/** Atomic bounded registry for active SSE connections. */
public final class ChangeFeedConnectionRegistry implements AutoCloseable {
	private final int globalLimit;
	private final int sessionLimit;
	private final int userSurfaceLimit;
	private final int workspaceLimit;
	private final Map<String, Integer> sessions = new HashMap<>();
	private final Map<String, Integer> users = new HashMap<>();
	private final Map<String, Integer> workspaces = new HashMap<>();
	private int global;

	public ChangeFeedConnectionRegistry(int globalLimit, int sessionLimit, int userSurfaceLimit, int workspaceLimit) {
		this.globalLimit = positive(globalLimit, "globalLimit");
		this.sessionLimit = positive(sessionLimit, "sessionLimit");
		this.userSurfaceLimit = positive(userSurfaceLimit, "userSurfaceLimit");
		this.workspaceLimit = positive(workspaceLimit, "workspaceLimit");
	}

	public synchronized Lease reserve(String session, String userSurface, String workspace) {
		if (global >= globalLimit || count(sessions, session) >= sessionLimit
				|| count(users, userSurface) >= userSurfaceLimit || count(workspaces, workspace) >= workspaceLimit) {
			throw new ChangeFeedCapacityException();
		}
		increment(sessions, session); increment(users, userSurface); increment(workspaces, workspace); global++;
		return new Lease(session, userSurface, workspace);
	}

	private static int positive(int value, String name) { if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value; }
	private static int count(Map<String, Integer> values, String key) { return values.getOrDefault(key, 0); }
	private static void increment(Map<String, Integer> values, String key) { values.merge(key, 1, Integer::sum); }
	private static void decrement(Map<String, Integer> values, String key) { values.computeIfPresent(key, (ignored, value) -> value == 1 ? null : value - 1); }

	public final class Lease implements AutoCloseable {
		private final String session; private final String userSurface; private final String workspace; private boolean released;
		private Lease(String session, String userSurface, String workspace) { this.session = session; this.userSurface = userSurface; this.workspace = workspace; }
		@Override public void close() { synchronized (ChangeFeedConnectionRegistry.this) { if (released) return; released = true; decrement(sessions, session); decrement(users, userSurface); decrement(workspaces, workspace); global--; } }
	}

	@Override public synchronized void close() { sessions.clear(); users.clear(); workspaces.clear(); global = 0; }
}
