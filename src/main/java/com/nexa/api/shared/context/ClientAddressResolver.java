package com.nexa.api.shared.context;

/** Resolves a client address from the direct peer and an optional forwarded chain. */
@FunctionalInterface
public interface ClientAddressResolver {
	String resolve(String remoteAddress, String forwardedFor);
}
