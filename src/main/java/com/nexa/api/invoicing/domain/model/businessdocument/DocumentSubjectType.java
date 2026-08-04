package com.nexa.api.invoicing.domain.model.businessdocument;

/** Stable resources that may receive a future document. */
public enum DocumentSubjectType {
    SALES_ORDER, PURCHASE_REQUEST, RECEIVABLE, PAYMENT,
    DISPATCH_ORDER,
    PROOF_OF_DELIVERY,
    DELIVERY_INCIDENT
}
