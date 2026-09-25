package com.nexa.api.tenantaccessgovernance.iam.presentation.transport;

/** Stable native-session transport contract enforced by the inbound security edge. */
public final class AuthenticationTransport {
	public static final String NATIVE_CLIENT_HEADER = "X-Nexa-Client";
	private static final String NATIVE_CLIENT_VALUE = "NATIVE";
	private static final String IDENTITY_SIGN_IN = "/api/v1/authentication/identity-sign-in";
	private static final String ACCESS_CONTEXTS = "/api/v1/me/access-contexts";
	private static final String ACCESS_CONTEXT_SELECTIONS = "/api/v1/me/access-context-selections";
	public static final String ACCESS_CONTEXT_TICKET_HEADER = "X-Nexa-Context-Ticket";

	private AuthenticationTransport() { }

	public static boolean isNativeSessionTransport(String origin, String clientHeader, String requestUri) {
		if (origin != null && !origin.isBlank()) return false;
		if (!NATIVE_CLIENT_VALUE.equalsIgnoreCase(clientHeader)) return false;
		return switch (requestUri) {
			case "/api/v1/authentication/sign-in", "/api/v1/authentication/refresh", "/api/v1/authentication/sign-out",
					IDENTITY_SIGN_IN, ACCESS_CONTEXTS, ACCESS_CONTEXT_SELECTIONS -> true;
			default -> false;
		};
	}

	public static boolean requiresNativeAccessContextTransport(String requestUri) {
		return IDENTITY_SIGN_IN.equals(requestUri);
	}

	public static boolean isAccessContextAuthorityEndpoint(String requestUri) {
		return ACCESS_CONTEXTS.equals(requestUri) || ACCESS_CONTEXT_SELECTIONS.equals(requestUri);
	}
}
