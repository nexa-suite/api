package com.nexa.api.iam.application.port.out;

public interface PasswordHashPort {
    String encode(String rawPassword);
}
