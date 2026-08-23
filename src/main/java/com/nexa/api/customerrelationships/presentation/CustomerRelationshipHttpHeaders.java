package com.nexa.api.customerrelationships.presentation;

import com.nexa.api.customerrelationships.application.exception.CustomerRelationshipPreconditionRequiredException;

public final class CustomerRelationshipHttpHeaders {
    private CustomerRelationshipHttpHeaders() {
    }

    public static long requireVersion(String value) {
        if (value == null || !value.trim().matches("\\\"?\\d+\\\"?")) {
            throw new CustomerRelationshipPreconditionRequiredException();
        }
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        } catch (NumberFormatException exception) {
            throw new CustomerRelationshipPreconditionRequiredException();
        }
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }
}
