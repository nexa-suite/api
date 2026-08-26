package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.port.out.OrganizationRegistrationPersistencePort;
import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcOrganizationRegistrationPersistenceAdapter implements OrganizationRegistrationPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcOrganizationRegistrationPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void save(OrganizationRegistration registration, RegistrationRequest request, Instant submittedAt) {
        if (!jdbc.query("select id from tenant_management.organization_registration where workspace_slug=?", (rs, row) -> rs.getObject(1), registration.workspaceSlug().value()).isEmpty()) throw new IamSecurityException("REGISTRATION_SLUG_CONFLICT");
        jdbc.update("insert into tenant_management.organization_registration (id,legal_name,display_name,normalized_legal_name,business_identifier,operation_category,storage_site_name,storage_site_address,founder_email,founder_display_name,workspace_name,workspace_slug,reference_plan,terms_version,terms_accepted_at,status,status_token_hash,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING_ACTIVATION',?,?,?,0)",
                registration.id().value(), request.legalName().trim(), request.displayName().trim(), request.legalName().trim().toLowerCase(java.util.Locale.ROOT), request.businessIdentifier() == null || request.businessIdentifier().isBlank() ? null : request.businessIdentifier().trim(), request.operationCategory(), request.storageSiteName(), request.storageSiteAddress(), registration.founder().email(), registration.founder().displayName(), request.workspaceName(), registration.workspaceSlug().value(), registration.plan().name(), registration.terms().version(), java.sql.Timestamp.from(submittedAt), registration.statusTokenHash().value(), java.sql.Timestamp.from(submittedAt), java.sql.Timestamp.from(submittedAt));
    }
    @Override public Registration findStatus(UUID registrationId, String statusTokenHash) {
        String query = statusTokenHash == null ? "select id,status,created_at from tenant_management.organization_registration where id=?" : "select id,status,created_at from tenant_management.organization_registration where id=? and status_token_hash=?";
        var rows = statusTokenHash == null ? jdbc.query(query, (rs, row) -> new Registration(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getTimestamp(3).toInstant()), registrationId)
                : jdbc.query(query, (rs, row) -> new Registration(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getTimestamp(3).toInstant()), registrationId, statusTokenHash);
        if (rows.isEmpty()) throw new com.nexa.api.shared.application.error.ApiResourceNotFoundException("organization registration status");
        return rows.get(0);
    }
}
