package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

/** Installs the server-authorized, transaction-local context required by registration RLS. */
final class JdbcOrganizationRegistrationRlsContext {
    private static final String TOKEN_SCOPE_SQL = "select set_config('app.organization_registration_id', ?, true) || "
            + "set_config('app.organization_registration_token_hash', ?, true) || "
            + "set_config('app.organization_registration_write_mode', ?, true)";
    private static final String OPERATOR_SCOPE_SQL = "select set_config('app.organization_registration_operator_id', ?, true)";
    private static final String TENANT_WORKSPACE_SCOPE_SQL = "select set_config('app.current_tenant_id', ?, true) || "
            + "set_config('app.current_workspace_id', ?, true)";

    private JdbcOrganizationRegistrationRlsContext() { }

    static void bindDraftCreate(JdbcTemplate jdbc, UUID registrationId, String tokenHash) {
        bindTokenScope(jdbc, registrationId, tokenHash, "DRAFT_CREATE");
    }

    static void bindDraftRead(JdbcTemplate jdbc, UUID registrationId, String tokenHash) {
        bindTokenScope(jdbc, registrationId, tokenHash, "DRAFT_READ");
    }

    static void bindDraftUpdate(JdbcTemplate jdbc, UUID registrationId, String tokenHash) {
        bindTokenScope(jdbc, registrationId, tokenHash, "DRAFT_UPDATE");
    }

    static void bindPublicSubmit(JdbcTemplate jdbc, UUID registrationId, String tokenHash) {
        bindTokenScope(jdbc, registrationId, tokenHash, "PUBLIC_SUBMIT");
    }

    static void bindPublicStatus(JdbcTemplate jdbc, UUID registrationId, String tokenHash) {
        bindTokenScope(jdbc, registrationId, tokenHash, "PUBLIC_STATUS");
    }

    static void bindOperator(JdbcTemplate jdbc, UUID registrationId) {
        Objects.requireNonNull(jdbc, "JDBC template is required");
        Objects.requireNonNull(registrationId, "Registration id is required");
        jdbc.queryForObject(OPERATOR_SCOPE_SQL, String.class, registrationId.toString());
    }

    static void bindTenantWorkspace(JdbcTemplate jdbc, UUID tenantId, UUID workspaceId) {
        Objects.requireNonNull(jdbc, "JDBC template is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(workspaceId, "Workspace id is required");
        jdbc.queryForObject(TENANT_WORKSPACE_SCOPE_SQL, String.class, tenantId.toString(), workspaceId.toString());
    }

    private static void bindTokenScope(JdbcTemplate jdbc, UUID registrationId, String tokenHash, String action) {
        Objects.requireNonNull(jdbc, "JDBC template is required");
        Objects.requireNonNull(registrationId, "Registration id is required");
        String normalizedHash = tokenHash == null ? "" : tokenHash.trim();
        if (!normalizedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Registration token hash must be a SHA-256 hex value");
        }
        jdbc.queryForObject(TOKEN_SCOPE_SQL, String.class, registrationId.toString(), normalizedHash, action);
    }
}
