package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PasswordCredentialJpaRepository extends JpaRepository<PasswordCredentialJpaEntity, UUID> {
}
