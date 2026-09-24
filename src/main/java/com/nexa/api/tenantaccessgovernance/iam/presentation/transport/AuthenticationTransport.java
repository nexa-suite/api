package com.nexa.api.tenantaccessgovernance.iam.presentation.transport;

/** Stable native-session transport contract enforced by the inbound security edge. */
public final class AuthenticationTransport {
	public static final String NATIVE_CLIENT_HEADER = "X-Nexa-Client";
	private static final String NATIVE_CLIENT_VALUE = "NATIVE";

	private AuthenticationTransport() { }

	public static boolean isNativeSessionTransport(String origin, String clientHeader, String requestUri) {
		if (origin != null && !origin.isBlank()) return false;
		if (!NATIVE_CLIENT_VALUE.equalsIgnoreCase(clientHeader)) return false;
		return switch (requestUri) {
			case "/api/v1/authentication/sign-in", "/api/v1/authentication/refresh", "/api/v1/authentication/sign-out" -> true;
			default -> false;
		};
	}
}
