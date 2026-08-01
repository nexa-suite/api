package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_membership", schema = "tenant_management")
public class WorkspaceMembershipJpaEntity {
	@Id private UUID id;
	@Column(name = "workspace_id", nullable = false) private UUID workspaceId;
	@Column(name = "user_id", nullable = false) private UUID userId;
	@Column(name = "membership_type", nullable = false, length = 32) private String membershipType;
	@Column(nullable = false, length = 32) private String status;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;
	@Version @Column(nullable = false) private long version;
	protected WorkspaceMembershipJpaEntity() {}
	public UUID getId() { return id; }
	public UUID getWorkspaceId() { return workspaceId; }
	public UUID getUserId() { return userId; }
	public String getMembershipType() { return membershipType; }
	public String getStatus() { return status; }
}
