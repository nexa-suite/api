package com.nexa.api.shared.presentation.http;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTests {
	private final CorrelationIdFilter filter = new CorrelationIdFilter();

	@Test
	void generatesAndReturnsSafeIdWhenHeaderIsMissing() throws Exception {
		MockHttpServletResponse response = invoke(null);
		String id = response.getHeader(CorrelationIdFilter.HEADER_NAME);
		assertThat(id).isNotNull().matches("[A-Za-z0-9._-]{1,128}");
	}

	@Test
	void preservesValidIdAndReplacesTooLongOrUnsafeValues() throws Exception {
		assertThat(invoke("client.request-1").getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("client.request-1");
		assertThat(invoke("x".repeat(129)).getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo("x".repeat(129));
		assertThat(invoke("bad value").getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo("bad value");
	}

	@Test
	void clearsMdcAfterTheRequest() throws Exception {
		AtomicReference<String> observed = new AtomicReference<>();
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> observed.set(MDC.get(CorrelationIdFilter.HEADER_NAME)));
		assertThat(observed.get()).isEqualTo(response.getHeader(CorrelationIdFilter.HEADER_NAME));
		assertThat(MDC.get(CorrelationIdFilter.HEADER_NAME)).isNull();
	}

	private MockHttpServletResponse invoke(String id) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (id != null) request.addHeader(CorrelationIdFilter.HEADER_NAME, id);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
		return response;
	}
}
