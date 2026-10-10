package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Minimal, tenant-scoped Customer Account directory projection for authorized business workflows. */
public interface CustomerAccountDirectoryQuery {
    Page list(UUID tenantId, UUID workspaceId, String search, String status, int page, int size);

    /** Includes active and suspended accounts while preserving explicit Tenant and Workspace scope. */
    Optional<Entry> find(UUID tenantId, UUID workspaceId, UUID clientAccountId);

    record Entry(UUID id, String commercialName, String status) {
        public Entry {
            if (id == null || commercialName == null || commercialName.isBlank()
                    || status == null || status.isBlank()) {
                throw new IllegalArgumentException("Customer Account directory entry is incomplete");
            }
        }
    }

    record Page(List<Entry> items, int page, int size, long total) {
        public Page {
            items = List.copyOf(items == null ? List.of() : items);
            if (page < 0 || size < 1 || total < 0) {
                throw new IllegalArgumentException("Customer Account directory page is invalid");
            }
        }
    }
}
