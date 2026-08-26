package com.nexa.api.businessdocuments.domain;

import com.nexa.api.businessdocuments.domain.model.businessdocument.*;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class BusinessDocumentFoundationTests {
    @Test void identitiesAndOwnershipAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessDocumentId(" "));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubjectReference(DocumentSubjectType.SALES_ORDER, " "));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubjectSnapshot(" ", "workspace", DocumentSubjectType.SALES_ORDER, "id", null, "PENDING", false));
    }

    @Test void supportedTypesAreClosedAndAudiencesAreExplicit() {
        assertEquals(EnumSet.of(DocumentSubjectType.SALES_ORDER, DocumentSubjectType.PURCHASE_REQUEST,
                DocumentSubjectType.RECEIVABLE, DocumentSubjectType.PAYMENT, DocumentSubjectType.DISPATCH_ORDER,
                DocumentSubjectType.PROOF_OF_DELIVERY, DocumentSubjectType.DELIVERY_INCIDENT),
                EnumSet.allOf(DocumentSubjectType.class));
        assertEquals(EnumSet.of(DocumentAudience.INTERNAL, DocumentAudience.BUYER), EnumSet.allOf(DocumentAudience.class));
    }

    @Test void buyerScopeRequiresClientAccountAndInternalScopeMayOmitIt() {
        DocumentSubjectSnapshot internal = new DocumentSubjectSnapshot("tenant", "workspace", DocumentSubjectType.DISPATCH_ORDER, "dispatch", null, "IN_ROUTE", true);
        assertNull(internal.clientAccountId());
        DocumentSubjectSnapshot buyer = new DocumentSubjectSnapshot("tenant", "workspace", DocumentSubjectType.PROOF_OF_DELIVERY, "pod", "client", "COMPLETED", true);
        assertEquals("client", buyer.clientAccountId());
        DocumentAudience.INTERNAL.requireClientAccount(null);
        assertThrows(IllegalArgumentException.class, () -> DocumentAudience.BUYER.requireClientAccount(null));
        DocumentAudience.BUYER.requireClientAccount(buyer.clientAccountId());
    }

    @Test void subjectKindsAreExplicit() {
        assertEquals(BusinessDocumentKind.POD_EVIDENCE, BusinessDocumentKind.valueOf("POD_EVIDENCE"));
        assertThrows(IllegalArgumentException.class, () -> DocumentSubjectType.valueOf("INVOICE"));
    }
}
