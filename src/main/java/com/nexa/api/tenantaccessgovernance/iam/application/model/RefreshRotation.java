package com.nexa.api.tenantaccessgovernance.iam.application.model;

public record RefreshRotation(Status status, SessionRecord session) {
	public enum Status { ROTATED, REUSED, INVALID }

	public RefreshRotation {
		if (status == null) throw new IllegalArgumentException("Refresh rotation status is required");
		if (status == Status.ROTATED && session == null) throw new IllegalArgumentException("Rotated session is required");
		if (status != Status.ROTATED && session != null) throw new IllegalArgumentException("Only a rotated result has a session");
	}

	public static RefreshRotation rotated(SessionRecord session) { return new RefreshRotation(Status.ROTATED, session); }
	public static RefreshRotation reused() { return new RefreshRotation(Status.REUSED, null); }
	public static RefreshRotation invalid() { return new RefreshRotation(Status.INVALID, null); }
}
