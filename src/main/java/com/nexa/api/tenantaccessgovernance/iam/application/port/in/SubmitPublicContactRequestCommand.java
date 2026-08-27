package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Public Website intake boundary. It acknowledges receipt without creating a Tenant. */
public interface SubmitPublicContactRequestCommand {
    Receipt submit(Command command, String clientAddress, String correlationId, String traceId);

    record Command(String requestType, String fullName, String email, String companyName, String message) { }
    record Receipt(UUID requestId, String requestType, String status, Instant receivedAt) { }
}
