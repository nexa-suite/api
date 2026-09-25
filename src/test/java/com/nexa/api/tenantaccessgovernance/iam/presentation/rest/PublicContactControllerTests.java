package com.nexa.api.tenantaccessgovernance.iam.presentation.rest;

import com.nexa.api.edge.security.TrustedClientAddressResolver;
import com.nexa.api.shared.context.RequestMetadata;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SubmitPublicContactRequestCommand;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicContactControllerTests {
	@Test
	void passesHttpAddressAndTraceMetadataThroughTrustedProxyResolver() {
		UUID requestId = UUID.randomUUID();
		SubmitPublicContactRequestCommand command = (payload, address, correlation, trace) -> {
			assertThat(address).isEqualTo("198.51.100.20");
			assertThat(correlation).isEqualTo("correlation-1");
			assertThat(trace).isEqualTo("trace-1");
			return new SubmitPublicContactRequestCommand.Receipt(requestId, "DEMO", "RECEIVED", Instant.EPOCH);
		};
		var controller = new PublicContactController(command, new TrustedClientAddressResolver("10.0.0.10"));
		var request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.10");
		request.addHeader("X-Forwarded-For", "198.51.100.20, 10.0.0.10");
		request.addHeader("X-Trace-ID", "trace-1");
		request.setAttribute(RequestMetadata.CORRELATION_ID_ATTRIBUTE, "correlation-1");

		var response = controller.submit(request,
				new PublicContactController.Request("DEMO", "Test User", "test@example.com", "Example", "Please arrange a demo.")
		);

		assertThat(response.getStatusCode().value()).isEqualTo(202);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().requestId()).isEqualTo(requestId);
	}
}
