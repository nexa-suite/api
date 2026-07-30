package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceJpaRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {
	Optional<WorkspaceJpaEntity> findByTenantIdAndSlug(UUID tenantId, String slug);
	Optional<WorkspaceJpaEntity> findFirstBySlug(String slug);
}
