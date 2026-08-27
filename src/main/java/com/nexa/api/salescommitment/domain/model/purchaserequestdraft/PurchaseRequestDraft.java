package com.nexa.api.salescommitment.domain.model.purchaserequestdraft;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** State machine for the server-authoritative buyer request builder. */
public final class PurchaseRequestDraft {
    private PurchaseRequestDraft() { }

    public static PurchaseRequestDraftStatus status(boolean products, boolean destination, boolean route, boolean commercial) {
        if (!products) return PurchaseRequestDraftStatus.DRAFT;
        if (!destination) return PurchaseRequestDraftStatus.PRODUCTS_COMPLETE;
        if (!route) return PurchaseRequestDraftStatus.DESTINATION_COMPLETE;
        if (!commercial) return PurchaseRequestDraftStatus.ROUTE_VALIDATED;
        return PurchaseRequestDraftStatus.READY_TO_SUBMIT;
    }

    public static void requireMutable(PurchaseRequestDraftStatus status) {
        Objects.requireNonNull(status, "Draft status is required");
        if (status == PurchaseRequestDraftStatus.SUBMITTED) throw new IllegalStateException("Submitted draft cannot be changed");
    }

    public static Set<PurchaseRequestDraftStatus> states() {
        return Set.copyOf(EnumSet.allOf(PurchaseRequestDraftStatus.class));
    }
}
