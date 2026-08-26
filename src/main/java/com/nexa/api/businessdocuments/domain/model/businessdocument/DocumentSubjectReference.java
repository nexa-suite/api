package com.nexa.api.businessdocuments.domain.model.businessdocument;

import java.util.Objects;

/** Typed reference to an existing resource; it carries no document or storage behavior. */
public record DocumentSubjectReference(DocumentSubjectType type, String subjectId) {
    public DocumentSubjectReference {
        type = Objects.requireNonNull(type, "Document subject type is required");
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("Document subject id is required");
        subjectId = subjectId.trim();
        if (subjectId.length() > 128) throw new IllegalArgumentException("Document subject id is too long");
    }
}
