package com.nexa.api.businessdocuments.domain.model.businessdocument;

import java.util.Objects;

/** Minimal ownership/lifecycle projection for a future document subject. */
public record DocumentSubjectSnapshot(
        String tenantId,
        String workspaceId,
        DocumentSubjectType subjectType,
        String subjectId,
        String clientAccountId,
        String lifecycleState,
        boolean subjectExists) {
    public DocumentSubjectSnapshot {
        require(tenantId, "Tenant id");
        require(workspaceId, "Workspace id");
        subjectType = Objects.requireNonNull(subjectType, "Subject type is required");
        require(subjectId, "Subject id");
        require(lifecycleState, "Lifecycle state");
        if (clientAccountId != null && clientAccountId.isBlank()) throw new IllegalArgumentException("Client account id is invalid");
        tenantId = tenantId.trim();
        workspaceId = workspaceId.trim();
        subjectId = subjectId.trim();
        lifecycleState = lifecycleState.trim();
        clientAccountId = clientAccountId == null ? null : clientAccountId.trim();
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    }
}
