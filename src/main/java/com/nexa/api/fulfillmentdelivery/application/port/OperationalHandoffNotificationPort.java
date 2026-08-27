package com.nexa.api.fulfillmentdelivery.application.port;

/**
 * Scoped notification boundary for operational handoffs. Implementations must
 * enqueue or publish after the local transaction boundary; they must not call
 * external networks inline.
 */
public interface OperationalHandoffNotificationPort {
    void notify(Notification notification);

    record Notification(String tenantId, String workspaceId, String clientAccountId,
                        String dispatchOrderId, String eventType, String publicStatus,
                        long occurredAtEpochMillis) {
        public Notification {
            if (tenantId == null || workspaceId == null || dispatchOrderId == null
                    || eventType == null || eventType.isBlank() || publicStatus == null) {
                throw new IllegalArgumentException("Scoped operational notification is incomplete");
            }
        }
    }
}
