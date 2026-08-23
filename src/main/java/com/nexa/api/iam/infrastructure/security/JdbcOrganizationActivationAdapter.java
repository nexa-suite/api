package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.port.out.OrganizationActivationPersistencePort;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcOrganizationActivationAdapter implements OrganizationActivationPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcOrganizationActivationAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegistrationSnapshot> findForUpdate(UUID registrationId) {
        return jdbc.query("select id,legal_name,display_name,business_identifier,operation_category,workspace_name,workspace_slug,founder_email,founder_display_name,terms_version,status_token_hash,reference_plan,status,created_at,tenant_id,workspace_id,activated_founder_user_id from tenant_management.organization_registration where id=? for update",
                (rs, row) -> new RegistrationSnapshot(rs.getObject("id", UUID.class), rs.getString("legal_name"), rs.getString("display_name"),
                        rs.getString("business_identifier"), rs.getString("operation_category"), rs.getString("workspace_name"),
                        rs.getString("workspace_slug"), rs.getString("founder_email"), rs.getString("founder_display_name"),
                        rs.getString("terms_version"), rs.getString("status_token_hash"),
                        rs.getString("reference_plan"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("activated_founder_user_id", UUID.class)), registrationId)
                .stream().findFirst();
    }

    @Override
    public ActivatedOrganization createActivatedOrganization(OrganizationRegistration registration, OrganizationSeed organization,
            String workspaceName, String initialPasswordHash, Instant now) {
        String founderEmail = registration.founder().email();
        UUID existingUser = jdbc.query("select id from iam.user_account where normalized_email=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), founderEmail).stream().findFirst().orElse(null);
        if (existingUser != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from tenant_management.workspace_membership where user_id=? and membership_type='BUYER' and status='ACTIVE')",
                Boolean.class, existingUser))) {
            throw new IamSecurityException("FOUNDER_EMAIL_INCOMPATIBLE");
        }

        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) values (?,?,?,'ACTIVE',?,?,0)",
                tenantId, organization.displayName(), registration.workspaceSlug().value(), timestamp(now), timestamp(now));
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) values (?,?,?,?,'ACTIVE',?,?,0)",
                workspaceId, tenantId, workspaceName, registration.workspaceSlug().value(), timestamp(now), timestamp(now));
        jdbc.update("insert into tenant_management.organization_settings (tenant_id,legal_name,display_name,business_identifier,operation_category,version,updated_at) values (?,?,?,?,?,0,?)",
                tenantId, organization.legalName(), organization.displayName(), nullable(organization.businessIdentifier()), organization.operationCategory(), timestamp(now));

        UUID userId = existingUser;
        if (userId == null) {
            userId = UUID.randomUUID();
            jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,'es','ACTIVE',?,?,0)",
                    userId, founderEmail, founderEmail, founderEmail, founderEmail, registration.founder().displayName(), timestamp(now), timestamp(now));
            jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) values (?,?,'bcrypt',?)",
                    userId, initialPasswordHash, timestamp(now));
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)",
                membershipId, workspaceId, userId, timestamp(now), timestamp(now));
        return new ActivatedOrganization(tenantId, workspaceId, userId, membershipId, founderEmail);
    }

    @Override
    public void markActivated(UUID registrationId, UUID tenantId, UUID workspaceId, UUID founderUserId, Instant now) {
        int changed = jdbc.update("update tenant_management.organization_registration set status='ACTIVE',tenant_id=?,workspace_id=?,activated_founder_user_id=?,updated_at=?,version=version+1 where id=? and status='PENDING_ACTIVATION'",
                tenantId, workspaceId, founderUserId, timestamp(now), registrationId);
        if (changed != 1) throw new ApiResourceNotFoundException("pending organization registration");
    }

    @Override
    public void markRejected(UUID registrationId, String reason, Instant now) {
        int changed = jdbc.update("update tenant_management.organization_registration set status='REJECTED',rejection_reason=?,updated_at=?,version=version+1 where id=? and status='PENDING_ACTIVATION'",
                reason, timestamp(now), registrationId);
        if (changed != 1) throw new ApiResourceNotFoundException("pending organization registration");
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
