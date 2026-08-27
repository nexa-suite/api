package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionJpaRepository extends JpaRepository<RefreshSessionJpaEntity, UUID> {
	Optional<RefreshSessionJpaEntity> findByTokenHash(String tokenHash);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from RefreshSessionJpaEntity s where s.tokenHash = :tokenHash")
	Optional<RefreshSessionJpaEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
	List<RefreshSessionJpaEntity> findByFamilyId(UUID familyId);
}
