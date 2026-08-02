package com.nexa.api.iam.application.port.in;

public interface ResetPasswordCommand {
    void reset(String token, String newPassword, String correlationId, String traceId);
}
