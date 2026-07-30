package com.nexa.api.iam.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PasswordCredentialJpaRepository extends JpaRepository<PasswordCredentialJpaEntity, UUID> {
}
