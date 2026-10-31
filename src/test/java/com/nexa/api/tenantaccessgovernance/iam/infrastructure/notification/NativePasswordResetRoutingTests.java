package com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification;

import com.nexa.api.tenantaccessgovernance.iam.application.model.PasswordResetDeliveryChannel;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.RequestPasswordResetCommand;
import com.nexa.api.tenantaccessgovernance.iam.presentation.rest.IamSecurityController;
import com.nexa.api.tenantaccessgovernance.iam.presentation.transport.AuthenticationTransport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NativePasswordResetRoutingTests {
	private static final String PLATFORM_WEB = "https://platform.nexa.com/reset-password";
	private static final String PORTAL_WEB = "https://portal.nexa.com/reset-password";
	private static final String PLATFORM_NATIVE = "https://operations.nexa.com/reset-password";
	private static final String PORTAL_NATIVE = "https://buyer.nexa.com/reset-password";

	@Test
	void selectsConfiguredResetUrlBySurfaceAndTransport() {
		assertThat(link("PLATFORM", PasswordResetDeliveryChannel.WEB, "web-token"))
				.isEqualTo(PLATFORM_WEB + "#token=web-token");
		assertThat(link("PORTAL", PasswordResetDeliveryChannel.WEB, "web-token"))
				.isEqualTo(PORTAL_WEB + "#token=web-token");
		assertThat(link("PLATFORM", PasswordResetDeliveryChannel.NATIVE, "native-token"))
				.isEqualTo(PLATFORM_NATIVE + "#token=native-token");
		assertThat(link("PORTAL", PasswordResetDeliveryChannel.NATIVE, "native-token"))
				.isEqualTo(PORTAL_NATIVE + "#token=native-token");
	}

	@Test
	void missingNativeUrlFailsClosedWithoutFallingBackToWebUrl() {
		assertThatThrownBy(() -> SmtpPasswordResetDeliveryAdapter.resetLink("PORTAL", PasswordResetDeliveryChannel.NATIVE,
				"token", PLATFORM_WEB, PORTAL_WEB, PLATFORM_NATIVE, ""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Configured password-reset URL is required for NATIVE PORTAL");
	}

	@Test
	void invalidSurfaceDoesNotFallBackToPlatformRoute() {
		assertThatThrownBy(() -> link("UNKNOWN", PasswordResetDeliveryChannel.NATIVE, "token"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Password-reset surface is invalid");
	}

	@Test
	void resetTokenIsEncodedAsOneFragmentValue() {
		String link = link("PORTAL", PasswordResetDeliveryChannel.NATIVE, "token&redirect=https://evil.example");
		assertThat(link).startsWith(PORTAL_NATIVE + "#token=token%26redirect%3D");
		assertThat(link).doesNotContain("&redirect=");
	}

	@Test
	void nativeResetTransportIsLimitedToResetEndpointsWithoutOrigin() {
		assertThat(AuthenticationTransport.isNativeSessionTransport(null, "NATIVE", "/api/v1/auth/password-reset-requests")).isFalse();
		assertThat(AuthenticationTransport.isNativeSessionTransport(null, "NATIVE", "/api/v1/auth/password-resets")).isFalse();
		assertThat(com.nexa.api.tenantaccessgovernance.iam.presentation.transport.PasswordResetRequestTransport
				.isNativePortalRequest("POST", null, "NATIVE", "PORTAL", "/api/v1/auth/password-reset-requests")).isTrue();
		assertThat(AuthenticationTransport.isNativeSessionTransport("https://portal.nexa.com", "NATIVE",
				"/api/v1/auth/password-reset-requests")).isFalse();
		assertThat(AuthenticationTransport.isNativeSessionTransport(null, "NATIVE",
				"/api/v1/auth/password-reset-requests/other")).isFalse();
	}

	@Test
	void controllerSendsNativeTransportSelectionIntoResetRequestUseCase() {
		RequestPasswordResetCommand useCase = mock(RequestPasswordResetCommand.class);
		IamSecurityController controller = new IamSecurityController(null, null, null, null, null, null, useCase,
				null, null, null, null, null, (remote, forwarded) -> remote);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password-reset-requests");
		request.addHeader(AuthenticationTransport.NATIVE_CLIENT_HEADER, "NATIVE");
		request.addHeader("X-Nexa-Surface", "PORTAL");

		controller.requestReset(request, new IamSecurityController.ResetRequest("buyer@example.com", "PORTAL"));

		verify(useCase).request(eq("buyer@example.com"), eq("PORTAL"),
				eq(PasswordResetDeliveryChannel.NATIVE), anyString(), anyString(), anyString());
	}

	@Test
	void legacyOutboxResetPayloadRemainsOnWebRoute() {
		assertThat(SecurityNotificationOutboxWorker.resetChannel("token=legacy-token\nexpiresAt=2026-10-20T12:00:00Z"))
				.isEqualTo(PasswordResetDeliveryChannel.WEB);
		assertThat(SecurityNotificationOutboxWorker.resetChannel("channel=NATIVE\ntoken=native-token\nexpiresAt=2026-10-20T12:00:00Z"))
				.isEqualTo(PasswordResetDeliveryChannel.NATIVE);
	}

	private static String link(String surface, PasswordResetDeliveryChannel channel, String token) {
		return SmtpPasswordResetDeliveryAdapter.resetLink(surface, channel, token,
				PLATFORM_WEB, PORTAL_WEB, PLATFORM_NATIVE, PORTAL_NATIVE);
	}

}
