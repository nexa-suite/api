package com.nexa.api.edge.security;

import com.nexa.api.shared.context.ClientAddressResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves a privacy-throttle address without trusting arbitrary forwarded headers. */
@Component
public final class TrustedClientAddressResolver implements ClientAddressResolver {
    private final Set<String> trustedProxies;
    public TrustedClientAddressResolver(@Value("${nexa.security.trusted-proxies:}") String configured) {
        trustedProxies = Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
    public String resolve(String remoteAddress, String forwardedFor) {
        String remote = normalize(remoteAddress);
        if (trustedProxies.isEmpty() || !trustedProxies.contains(remote)) return remote;
        if (forwardedFor == null || forwardedFor.isBlank()) return remote;
        String[] hops = forwardedFor.split(",");
        for (int index = hops.length - 1; index >= 0; index--) {
            String hop = normalize(hops[index]);
            if (!trustedProxies.contains(hop)) return hop;
        }
        return remote;
    }
    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        try { return InetAddress.getByName(value.trim()).getHostAddress(); }
        catch (Exception ignored) { return "unknown"; }
    }
}
