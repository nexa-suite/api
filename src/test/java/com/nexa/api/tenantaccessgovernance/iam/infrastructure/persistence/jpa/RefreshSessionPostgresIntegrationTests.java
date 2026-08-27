package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.RefreshTokenReuseDetectedException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.RefreshSessionCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SignInCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.RefreshSessionUseCase;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.SignInUseCase;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"nexa.security.reset.throttle-key=integration-reset-throttle-key-012345678901234567890123456789",
		"nexa.security.notification-outbox-key=integration-notification-outbox-key-012345678901234567890123456789",
		"nexa.security.system-operator-token=integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"
})
@Testcontainers(disabledWithoutDocker = true)
class RefreshSessionPostgresIntegrationTests {
	private static final String PASSWORD = "refresh-test-password";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	@Autowired
	private SignInUseCase signIn;

	@Autowired
	private RefreshSessionUseCase refresh;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("NEXA_DATABASE_URL", POSTGRES::getJdbcUrl);
		registry.add("NEXA_DATABASE_USERNAME", POSTGRES::getUsername);
		registry.add("NEXA_DATABASE_PASSWORD", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("NEXA_FLYWAY_ENABLED", () -> "true");
		registry.add("nexa.jdbc.adapters-enabled", () -> "true");
		registry.add("nexa.security.allow-ephemeral-keys", () -> "true");
		registry.add("nexa.security.issuer", () -> "http://test.local");
		registry.add("nexa.security.audience", () -> "nexa-test");
		registry.add("nexa.security.refresh-token-ttl", () -> "PT30M");
		registry.add("NEXA_DEV_BOOTSTRAP_ENABLED", () -> "true");
		registry.add("NEXA_DEV_TENANT_NAME", () -> "ICISA Test");
		registry.add("NEXA_DEV_TENANT_SLUG", () -> "icisa-test");
		registry.add("NEXA_DEV_WORKSPACE_NAME", () -> "ICISA Test Workspace");
		registry.add("NEXA_DEV_WORKSPACE_SLUG", () -> "icisa-test");
		registry.add("NEXA_DEV_OWNER_EMAIL", () -> "owner@icisa-test.local");
		registry.add("NEXA_DEV_OWNER_PASSWORD", () -> PASSWORD);
		registry.add("NEXA_DEV_SALES_EMAIL", () -> "sales@icisa-test.local");
		registry.add("NEXA_DEV_SALES_PASSWORD", () -> PASSWORD);
		registry.add("NEXA_DEV_WAREHOUSE_EMAIL", () -> "warehouse@icisa-test.local");
		registry.add("NEXA_DEV_WAREHOUSE_PASSWORD", () -> PASSWORD);
		registry.add("NEXA_DEV_LOGISTICS_EMAIL", () -> "logistics@icisa-test.local");
		registry.add("NEXA_DEV_LOGISTICS_PASSWORD", () -> PASSWORD);
		registry.add("NEXA_DEV_BUYER_EMAIL", () -> "buyer@icisa-test.local");
		registry.add("NEXA_DEV_BUYER_PASSWORD", () -> PASSWORD);
	}

	@Test
    void refreshTokenReuseRevokesReplacementSessionFamily() {
		var first = signIn.signIn(new SignInCommand("owner@icisa-test.local", PASSWORD, "icisa-test", ClientSurface.PLATFORM));
		var replacement = refresh.refresh(new RefreshSessionCommand(first.refreshToken(), ClientSurface.PLATFORM));

		assertThatThrownBy(() -> refresh.refresh(new RefreshSessionCommand(first.refreshToken(), ClientSurface.PLATFORM)))
				.isInstanceOf(RefreshTokenReuseDetectedException.class);
		assertThatThrownBy(() -> refresh.refresh(new RefreshSessionCommand(replacement.refreshToken(), ClientSurface.PLATFORM)))
				.isInstanceOf(RefreshTokenReuseDetectedException.class);

		UUID familyId = jdbc.queryForObject("select family_id from iam.refresh_session where id = ?",
				UUID.class, UUID.fromString(first.sessionId().value()));
		Integer revokedSessions = jdbc.queryForObject(
				"select count(*) from iam.refresh_session where family_id = ? and revoked_at is not null",
				Integer.class, familyId);
        assertThat(revokedSessions).isEqualTo(2);
    }

    @Test
    void refreshTokenReuseIsDetectedAfterMembershipLosesItsActivePolicy() {
        var first = signIn.signIn(new SignInCommand("owner@icisa-test.local", PASSWORD, "icisa-test", ClientSurface.PLATFORM));
        var replacement = refresh.refresh(new RefreshSessionCommand(first.refreshToken(), ClientSurface.PLATFORM));
        UUID membershipId = jdbc.queryForObject("select membership_id from iam.refresh_session where id=?",
                UUID.class, UUID.fromString(first.sessionId().value()));
        jdbc.update("update tenant_management.workspace_membership set status='SUSPENDED' where id=?", membershipId);
        try {
            assertThatThrownBy(() -> refresh.refresh(new RefreshSessionCommand(first.refreshToken(), ClientSurface.PLATFORM)))
                    .isInstanceOf(RefreshTokenReuseDetectedException.class);
            assertThatThrownBy(() -> refresh.refresh(new RefreshSessionCommand(replacement.refreshToken(), ClientSurface.PLATFORM)))
                    .isInstanceOf(RefreshTokenReuseDetectedException.class);
        } finally {
            jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?", membershipId);
        }
    }
}
