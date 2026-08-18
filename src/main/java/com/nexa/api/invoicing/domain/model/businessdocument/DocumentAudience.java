package com.nexa.api.invoicing.domain.model.businessdocument;

public enum DocumentAudience {
    INTERNAL,
    BUYER;

    public void requireClientAccount(String clientAccountId) {
        if (this == BUYER && (clientAccountId == null || clientAccountId.isBlank())) {
            throw new IllegalArgumentException("Buyer document audience requires a client account");
        }
    }
}
