package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientAddressResolverTests {
    @Test
    void ignoresForwardedAddressWhenRemoteIsNotTrusted() {
        var resolver = new TrustedClientAddressResolver("10.0.0.10");

        assertThat(resolver.resolve("198.51.100.20", "203.0.113.9, 10.0.0.10")).isEqualTo("198.51.100.20");
    }

    @Test
    void resolvesFirstUntrustedHopFromRightWhenProxyIsTrusted() {
        var resolver = new TrustedClientAddressResolver("10.0.0.10, 10.0.0.11");

        assertThat(resolver.resolve("10.0.0.10", "203.0.113.9, 10.0.0.11, 10.0.0.10")).isEqualTo("203.0.113.9");
    }

    @Test
    void usesRemoteAddressWithoutProxyConfiguration() {
        var resolver = new TrustedClientAddressResolver("");

        assertThat(resolver.resolve("198.51.100.20", "203.0.113.9")).isEqualTo("198.51.100.20");
    }
}
