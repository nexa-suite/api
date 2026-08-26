package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface AuthenticationFailureJpaRepository extends JpaRepository<AuthenticationFailureJpaEntity, UUID> {
	Optional<AuthenticationFailureJpaEntity> findByNormalizedIdentifierAndClientFingerprint(String normalizedIdentifier,
			String clientFingerprint);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from AuthenticationFailureJpaEntity f where f.normalizedIdentifier = :identifier and f.clientFingerprint = :fingerprint")
	Optional<AuthenticationFailureJpaEntity> findForUpdate(@Param("identifier") String identifier,
			@Param("fingerprint") String fingerprint);
}
