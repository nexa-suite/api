package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, UUID> {}
