package com.nexa.api.iam.application.port.in;

public interface RequestPasswordResetCommand {
    String request(String email, String surface, String clientAddress, String correlationId, String traceId);
}
