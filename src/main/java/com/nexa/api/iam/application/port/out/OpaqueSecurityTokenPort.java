package com.nexa.api.iam.application.port.out;

/** Generates and hashes opaque bearer material; raw values never cross persistence boundaries. */
public interface OpaqueSecurityTokenPort {
    String generate();
    String sha256(String opaqueValue);
}
