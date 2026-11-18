package com.nexa.api.tenantaccessgovernance.iam.application.exception;

public final class LegalDocumentChangedException extends RuntimeException {
    public LegalDocumentChangedException() { super("The legal document has changed; retrieve the current version"); }
}
