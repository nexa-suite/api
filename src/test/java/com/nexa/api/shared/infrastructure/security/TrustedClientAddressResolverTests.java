package com.nexa.api.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientAddressResolverTests {
    @Test
    void ignoresForwardedAddressWhenRemoteIsNotTrusted() {
        var resolver = new TrustedClientAddressResolver("10.0.0.10");
        var request = request("198.51.100.20", "203.0.113.9, 10.0.0.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void resolvesFirstUntrustedHopFromRightWhenProxyIsTrusted() {
        var resolver = new TrustedClientAddressResolver("10.0.0.10, 10.0.0.11");
        var request = request("10.0.0.10", "203.0.113.9, 10.0.0.11, 10.0.0.10");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void usesRemoteAddressWithoutProxyConfiguration() {
        var resolver = new TrustedClientAddressResolver("");

        assertThat(resolver.resolve(request("198.51.100.20", "203.0.113.9"))).isEqualTo("198.51.100.20");
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
