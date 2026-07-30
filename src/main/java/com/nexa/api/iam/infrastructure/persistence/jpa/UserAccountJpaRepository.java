package com.nexa.api.iam.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountJpaRepository extends JpaRepository<UserAccountJpaEntity, UUID> {
	Optional<UserAccountJpaEntity> findByNormalizedEmailOrNormalizedUsername(String normalizedEmail, String normalizedUsername);
}
