package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant", schema = "tenant_management")
public class TenantJpaEntity {
	@Id private UUID id;
	@Column(nullable = false, length = 160) private String name;
	@Column(nullable = false, unique = true, length = 80) private String slug;
	@Column(nullable = false, length = 32) private String status;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;
	@Column(nullable = false) private long version;
	protected TenantJpaEntity() {}
	public UUID getId() { return id; }
	public String getName() { return name; }
	public String getSlug() { return slug; }
	public String getStatus() { return status; }
}
