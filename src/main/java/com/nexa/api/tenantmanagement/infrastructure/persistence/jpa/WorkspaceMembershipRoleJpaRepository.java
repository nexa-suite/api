package com.nexa.api.tenantmanagement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkspaceMembershipRoleJpaRepository extends JpaRepository<WorkspaceMembershipRoleJpaEntity, WorkspaceMembershipRoleJpaEntity.Key> {
    List<WorkspaceMembershipRoleJpaEntity> findByMembershipId(UUID membershipId);

    @Query("select r from WorkspaceMembershipRoleJpaEntity r where r.tenantId = :tenantId and r.workspaceId = :workspaceId and r.role = :role")
    List<WorkspaceMembershipRoleJpaEntity> findByScopeAndRole(@Param("tenantId") UUID tenantId, @Param("workspaceId") UUID workspaceId, @Param("role") String role);
}
