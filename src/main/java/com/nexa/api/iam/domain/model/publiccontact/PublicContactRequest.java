package com.nexa.api.iam.domain.model.publiccontact;

import com.nexa.api.iam.domain.model.useraccount.EmailAddress;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Small public intake aggregate; it never provisions a Tenant or identity. */
public final class PublicContactRequest {
    public enum Type { DEMO, CONTACT }

    private final UUID id;
    private final Type type;
    private final String fullName;
    private final String email;
    private final String companyName;
    private final String message;
    private final Instant receivedAt;

    private PublicContactRequest(UUID id, Type type, String fullName, String email, String companyName,
            String message, Instant receivedAt) {
        this.id = Objects.requireNonNull(id, "Contact request id is required");
        this.type = Objects.requireNonNull(type, "Contact request type is required");
        this.fullName = required(fullName, 2, 160, "Contact name");
        this.email = new EmailAddress(email).value();
        this.companyName = optional(companyName, 160, "Company name");
        this.message = required(message, 20, 4000, "Contact message");
        this.receivedAt = Objects.requireNonNull(receivedAt, "Contact request timestamp is required");
    }

    public static PublicContactRequest receive(UUID id, String type, String fullName, String email, String companyName,
            String message, Instant receivedAt) {
        Type parsed;
        try {
            parsed = Type.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Contact request type is invalid", exception);
        }
        return new PublicContactRequest(id, parsed, fullName, email, companyName, message, receivedAt);
    }

    public UUID id() { return id; }
    public Type type() { return type; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public String companyName() { return companyName; }
    public String message() { return message; }
    public Instant receivedAt() { return receivedAt; }

    private static String required(String value, int min, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < min || normalized.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }
}
