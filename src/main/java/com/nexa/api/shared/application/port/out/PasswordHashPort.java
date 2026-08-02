package com.nexa.api.shared.application.port.out;

/** Hashes credentials at the infrastructure boundary without exposing a concrete algorithm. */
public interface PasswordHashPort {
    String encode(String rawPassword);
}
