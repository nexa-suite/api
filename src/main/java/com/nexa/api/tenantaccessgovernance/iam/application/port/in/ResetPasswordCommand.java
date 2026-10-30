package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

public interface ResetPasswordCommand {
    void reset(String token, String newPassword, String clientAddress, String correlationId, String traceId);
}
