package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace", schema = "tenant_management")
public class WorkspaceJpaEntity {
	@Id private UUID id;
	@Column(name = "tenant_id", nullable = false) private UUID tenantId;
	@Column(nullable = false, length = 160) private String name;
	@Column(nullable = false, length = 80) private String slug;
	@Column(nullable = false, length = 32) private String status;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;
	@Column(nullable = false) private long version;
	protected WorkspaceJpaEntity() {}
	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public String getName() { return name; }
	public String getSlug() { return slug; }
	public String getStatus() { return status; }
}
