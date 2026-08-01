package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "membership_role_assignment", schema = "tenant_management")
@IdClass(WorkspaceMembershipRoleJpaEntity.Key.class)
public class WorkspaceMembershipRoleJpaEntity {
    @Id @Column(name = "membership_id", nullable = false) private UUID membershipId;
    @Id @Column(nullable = false, length = 32) private String role;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "assigned_at", nullable = false) private Instant assignedAt;

    protected WorkspaceMembershipRoleJpaEntity() { }

    public WorkspaceMembershipRoleJpaEntity(UUID membershipId, UUID tenantId, UUID workspaceId, String role, Instant assignedAt) {
        this.membershipId = Objects.requireNonNull(membershipId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.role = Objects.requireNonNull(role);
        this.assignedAt = Objects.requireNonNull(assignedAt);
    }

    public UUID getMembershipId() { return membershipId; }
    public String getRole() { return role; }

    public static final class Key implements Serializable {
        private UUID membershipId;
        private String role;
        public Key() { }
        public Key(UUID membershipId, String role) { this.membershipId = membershipId; this.role = role; }
        @Override public boolean equals(Object other) { return other instanceof Key key && Objects.equals(membershipId, key.membershipId) && Objects.equals(role, key.role); }
        @Override public int hashCode() { return Objects.hash(membershipId, role); }
    }
}
