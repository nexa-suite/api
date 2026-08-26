package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipJpaRepository extends JpaRepository<WorkspaceMembershipJpaEntity, UUID> {
	@Query(value = "select m.* from tenant_management.workspace_membership m "
			+ "join tenant_management.workspace w on w.id = m.workspace_id "
			+ "where m.user_id = :userId and w.tenant_id = :tenantId and w.id = :workspaceId",
			nativeQuery = true)
	Optional<WorkspaceMembershipJpaEntity> findForScope(@Param("userId") UUID userId,
			@Param("tenantId") UUID tenantId, @Param("workspaceId") UUID workspaceId);
	@Query(value = "select m.* from tenant_management.workspace_membership m "
			+ "join tenant_management.workspace w on w.id = m.workspace_id "
			+ "where m.user_id = :userId and lower(w.slug) = :workspaceSlug limit 1",
			nativeQuery = true)
	Optional<WorkspaceMembershipJpaEntity> findForUserAndWorkspaceSlug(@Param("userId") UUID userId,
			@Param("workspaceSlug") String workspaceSlug);
	Optional<WorkspaceMembershipJpaEntity> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
