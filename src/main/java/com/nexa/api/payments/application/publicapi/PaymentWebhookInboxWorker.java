package com.nexa.api.payments.application.publicapi;

/** Claims and dispatches only the Tenant-routed payment callbacks stored in the central inbox. */
public interface PaymentWebhookInboxWorker {
    void processPending();

    void processEvent(String eventId);
}
