package com.nexa.api.customerbuyerrelationships.domain.model.buyeraddress;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.contract.CustomerRelationshipInvariantViolation;

import java.util.Objects;
import java.util.UUID;

/** Personal delivery address owned by one Buyer membership, separate from Client Account addresses. */
public final class BuyerAddress {
    private final UUID id;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID buyerMembershipId;
    private final String label;
    private final Address address;
    private final boolean defaultAddress;
    private final boolean active;
    private final long version;

    private BuyerAddress(UUID id, UUID tenantId, UUID workspaceId, UUID buyerMembershipId, String label,
                         Address address, boolean defaultAddress, boolean active, long version) {
        this.id = Objects.requireNonNull(id, "Address id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant id is required");
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace id is required");
        this.buyerMembershipId = Objects.requireNonNull(buyerMembershipId, "Buyer membership id is required");
        this.label = required(label);
        this.address = Objects.requireNonNull(address, "Address is required");
        if (version < 0) throw new CustomerRelationshipInvariantViolation("Address version cannot be negative");
        this.defaultAddress = defaultAddress;
        this.active = active;
        this.version = version;
    }

    public static BuyerAddress create(UUID id, UUID tenantId, UUID workspaceId, UUID buyerMembershipId,
                                      String label, Address address) {
        return new BuyerAddress(id, tenantId, workspaceId, buyerMembershipId, label, address, false, true, 0);
    }

    public static BuyerAddress rehydrate(UUID id, UUID tenantId, UUID workspaceId, UUID buyerMembershipId,
                                         String label, Address address, boolean defaultAddress,
                                         boolean active, long version) {
        return new BuyerAddress(id, tenantId, workspaceId, buyerMembershipId, label, address,
                defaultAddress, active, version);
    }

    public BuyerAddress withDefault(boolean value, long newVersion) {
        return new BuyerAddress(id, tenantId, workspaceId, buyerMembershipId, label, address,
                value, active, newVersion);
    }

    public BuyerAddress deactivate(long newVersion) {
        return new BuyerAddress(id, tenantId, workspaceId, buyerMembershipId, label, address,
                false, false, newVersion);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID buyerMembershipId() { return buyerMembershipId; }
    public String label() { return label; }
    public Address address() { return address; }
    public boolean defaultAddress() { return defaultAddress; }
    public boolean active() { return active; }
    public long version() { return version; }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 120) {
            throw new CustomerRelationshipInvariantViolation("Address label is invalid");
        }
        return value.trim();
    }
}
