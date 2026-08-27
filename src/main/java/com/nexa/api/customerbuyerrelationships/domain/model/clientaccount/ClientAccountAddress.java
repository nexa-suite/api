package com.nexa.api.customerbuyerrelationships.domain.model.clientaccount;

import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;
import com.nexa.api.customerbuyerrelationships.contract.Address;

import java.util.Objects;
import java.util.UUID;

/** Address aggregate member. Scope identifiers are carried to make cross-tenant use impossible by construction. */
public final class ClientAccountAddress {
    private final UUID id;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final String clientAccountId;
    private final String label;
    private final Address address;
    private final boolean defaultAddress;
    private final boolean active;
    private final long version;

    private ClientAccountAddress(UUID id, UUID tenantId, UUID workspaceId, String clientAccountId,
                                 String label, Address address, boolean defaultAddress, boolean active, long version) {
        this.id = Objects.requireNonNull(id, "Address id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
        this.clientAccountId = required(clientAccountId, "Client account id");
        this.label = required(label, "Address label");
        this.address = Objects.requireNonNull(address, "Address is required");
        if (version < 0) throw new CustomerRelationshipInvariantViolation("Address version cannot be negative");
        this.defaultAddress = defaultAddress;
        this.active = active;
        this.version = version;
    }

    public static ClientAccountAddress create(UUID id, UUID tenantId, UUID workspaceId, String clientAccountId,
                                              String label, Address address, boolean defaultAddress) {
        return new ClientAccountAddress(id, tenantId, workspaceId, clientAccountId, label, address,
                defaultAddress, true, 0);
    }

    public static ClientAccountAddress rehydrate(UUID id, UUID tenantId, UUID workspaceId, String clientAccountId,
                                                String label, Address address, boolean defaultAddress, boolean active,
                                                long version) {
        return new ClientAccountAddress(id, tenantId, workspaceId, clientAccountId, label, address,
                defaultAddress, active, version);
    }

    public ClientAccountAddress withDefault(boolean value, long newVersion) {
        return new ClientAccountAddress(id, tenantId, workspaceId, clientAccountId, label, address,
                value, active, newVersion);
    }

    public ClientAccountAddress deactivate(long newVersion) {
        return new ClientAccountAddress(id, tenantId, workspaceId, clientAccountId, label, address,
                false, false, newVersion);
    }

    public boolean belongsTo(UUID requestedTenantId, UUID requestedWorkspaceId, String requestedClientAccountId) {
        return tenantId.equals(requestedTenantId) && workspaceId.equals(requestedWorkspaceId)
                && clientAccountId.equals(requestedClientAccountId);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID workspaceId() { return workspaceId; }
    public String clientAccountId() { return clientAccountId; }
    public String label() { return label; }
    public Address address() { return address; }
    public boolean defaultAddress() { return defaultAddress; }
    public boolean isDefault() { return defaultAddress; }
    public boolean active() { return active; }
    public long version() { return version; }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new CustomerRelationshipInvariantViolation(label + " is required");
        return value.trim();
    }
}
