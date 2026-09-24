package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

/** Generates and hashes opaque bearer material; raw values never cross persistence boundaries. */
public interface OpaqueSecurityTokenPortContract {
    String generate();
    String sha256(String opaqueValue);
}
