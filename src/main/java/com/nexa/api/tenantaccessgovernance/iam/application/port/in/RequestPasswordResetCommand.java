package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.PasswordResetDeliveryChannel;

public interface RequestPasswordResetCommand {
    String request(String email, String surface, String clientAddress, String correlationId, String traceId);

    default String request(String email, String surface, PasswordResetDeliveryChannel channel,
            String clientAddress, String correlationId, String traceId) {
        if (channel != PasswordResetDeliveryChannel.WEB) {
            throw new UnsupportedOperationException("Native password-reset requests are not configured");
        }
        return request(email, surface, clientAddress, correlationId, traceId);
    }
}
