package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

public interface RequestPasswordResetCommand {
    String request(String email, String surface, String clientAddress, String correlationId, String traceId);
}
