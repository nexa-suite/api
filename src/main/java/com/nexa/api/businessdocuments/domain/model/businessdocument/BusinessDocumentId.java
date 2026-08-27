package com.nexa.api.businessdocuments.domain.model.businessdocument;

import java.util.Locale;

/** Stable language for a future business-document identity; not a persisted aggregate. */
public record BusinessDocumentId(String value) {
    public BusinessDocumentId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Business document id is required");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (value.length() > 64 || !value.matches("[A-Z0-9-]+")) {
            throw new IllegalArgumentException("Business document id is invalid");
        }
    }

    @Override public String toString() { return value; }
}
