package com.nexa.api.tenantaccessgovernance.iam.presentation.transport;

/** Request-only native transport marker for Buyer Portal password-reset requests. */
public final class PasswordResetRequestTransport {
	public static final String REQUEST_PATH = "/api/v1/auth/password-reset-requests";
	public static final String SURFACE_HEADER = "X-Nexa-Surface";
	private static final String PORTAL_SURFACE = "PORTAL";
	private static final String NATIVE_CLIENT = "NATIVE";

	private PasswordResetRequestTransport() { }

	public static boolean isNativePortalRequest(String method, String origin, String clientHeader,
			String surfaceHeader, String requestUri) {
		return "POST".equalsIgnoreCase(method)
				&& origin == null
				&& NATIVE_CLIENT.equalsIgnoreCase(clientHeader)
				&& PORTAL_SURFACE.equals(surfaceHeader)
				&& REQUEST_PATH.equals(requestUri);
	}
}
