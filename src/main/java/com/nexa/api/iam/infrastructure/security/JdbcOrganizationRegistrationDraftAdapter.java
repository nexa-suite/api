package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.exception.OrganizationRegistrationDraftException;
import com.nexa.api.iam.application.onboarding.OrganizationRegistrationDraftModels;
import com.nexa.api.iam.application.onboarding.OrganizationRegistrationDraftPort;
import com.nexa.api.iam.application.port.out.OpaqueSecurityTokenPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** JDBC authority for public resumable onboarding drafts. */
@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcOrganizationRegistrationDraftAdapter implements OrganizationRegistrationDraftPort {
    private static final Set<String> SECRET_KEYS = Set.of("password", "passwordhash", "token", "resumetoken", "secret", "clientsecret");
    private static final Set<String> FORBIDDEN_COMMERCIAL_KEYS = Set.of("billing", "subscription", "entitlement", "paymentprovider");
    private static final Set<String> PLANS = Set.of("Starter", "Standard", "Professional", "Enterprise");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OpaqueSecurityTokenPort tokens;
    private final Clock clock;

    public JdbcOrganizationRegistrationDraftAdapter(JdbcTemplate jdbc, ObjectMapper mapper,
            OpaqueSecurityTokenPort tokens, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrganizationRegistrationDraftModels.Created create() {
        UUID id = UUID.randomUUID();
        String rawToken = tokens.generate();
        Instant now = clock.instant();
        jdbc.update("insert into tenant_management.organization_registration (id,status,status_token_hash,onboarding_data,last_completed_step,created_at,updated_at,version) values (?,'DRAFT',?,'{}'::jsonb,0,?,?,0)",
                id, tokens.sha256(rawToken), timestamp(now), timestamp(now));
        return new OrganizationRegistrationDraftModels.Created(read(id, rawToken), rawToken);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationRegistrationDraftModels.Draft get(UUID registrationId, String resumeToken) {
        return read(registrationId, resumeToken);
    }

    @Override
    @Transactional
    public OrganizationRegistrationDraftModels.Draft updateStep(UUID registrationId, String resumeToken,
            int expectedVersion, int step, Map<String, Object> values, String idempotencyKey) {
        validateStep(step, values);
        requireIdempotencyKey(idempotencyKey);
        RegistrationRow row = lock(registrationId, resumeToken);
        String requestHash = requestHash("STEP", step + "|" + canonical(values));
        IdempotencyRow prior = idempotency(registrationId, idempotencyKey);
        if (prior != null) {
            if (!prior.requestHash().equals(requestHash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
            return readByHash(registrationId, row.tokenHash());
        }
        ensureDraft(row);
        ensureVersion(row, expectedVersion);

        Map<String, Object> data = new LinkedHashMap<>(row.data());
        data.put("step" + step, safeCopy(values));
        Map<String, Object> all = flatten(data);
        Instant now = clock.instant();
        long nextVersion = row.version() + 1;
        int completed = Math.max(row.lastCompletedStep(), step);
        jdbc.update("update tenant_management.organization_registration set onboarding_data=?::jsonb,last_completed_step=?,legal_name=?,display_name=?,normalized_legal_name=?,business_identifier=?,operation_category=?,storage_site_name=?,storage_site_address=?,founder_email=?,founder_display_name=?,workspace_name=?,workspace_slug=?,reference_plan=?,terms_version=?,terms_accepted_at=?,updated_at=?,version=? where id=? and status='DRAFT' and version=?",
                json(data), completed, text(all, "legalName"), text(all, "displayName"), normalized(text(all, "legalName")), nullable(text(all, "businessIdentifier")),
                text(all, "operationCategory"), text(all, "storageSiteName"), text(all, "storageSiteAddress"), text(all, "founderEmail"), text(all, "founderDisplayName"),
                text(all, "workspaceName"), text(all, "workspaceSlug"), text(all, "referencePlan"), text(all, "termsVersion"), acceptedAt(all) ? Timestamp.from(now) : null,
                Timestamp.from(now), nextVersion, registrationId, expectedVersion);
        remember(registrationId, idempotencyKey, "STEP", requestHash, nextVersion, now);
        return readByHash(registrationId, row.tokenHash());
    }

    @Override
    @Transactional
    public OrganizationRegistrationDraftModels.Draft submit(UUID registrationId, String resumeToken,
            int expectedVersion, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        RegistrationRow row = lock(registrationId, resumeToken);
        String requestHash = requestHash("SUBMIT", registrationId.toString());
        IdempotencyRow prior = idempotency(registrationId, idempotencyKey);
        if (prior != null) {
            if (!prior.requestHash().equals(requestHash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
            return readByHash(registrationId, row.tokenHash());
        }
        ensureDraft(row);
        ensureVersion(row, expectedVersion);
        Map<String, Object> all = flatten(row.data());
        validateComplete(row.lastCompletedStep(), all);
        Instant now = clock.instant();
        long nextVersion = row.version() + 1;
        jdbc.update("update tenant_management.organization_registration set status='PENDING_ACTIVATION',last_completed_step=6,legal_name=?,display_name=?,normalized_legal_name=?,business_identifier=?,operation_category=?,storage_site_name=?,storage_site_address=?,founder_email=?,founder_display_name=?,workspace_name=?,workspace_slug=?,reference_plan=?,terms_version=?,terms_accepted_at=?,updated_at=?,version=? where id=? and status='DRAFT' and version=?",
                text(all, "legalName"), text(all, "displayName"), normalized(text(all, "legalName")), nullable(text(all, "businessIdentifier")),
                text(all, "operationCategory"), text(all, "storageSiteName"), text(all, "storageSiteAddress"), text(all, "founderEmail"), text(all, "founderDisplayName"),
                text(all, "workspaceName"), text(all, "workspaceSlug"), text(all, "referencePlan"), text(all, "termsVersion"), Timestamp.from(now),
                Timestamp.from(now), nextVersion, registrationId, expectedVersion);
        remember(registrationId, idempotencyKey, "SUBMIT", requestHash, nextVersion, now);
        return readByHash(registrationId, row.tokenHash());
    }

    private RegistrationRow lock(UUID id, String rawToken) {
        if (id == null || rawToken == null || rawToken.isBlank()) throw error("DRAFT_NOT_FOUND");
        String hash = tokens.sha256(rawToken);
        return jdbc.query("select id,status,status_token_hash,onboarding_data::text,last_completed_step,version,created_at,updated_at from tenant_management.organization_registration where id=? and status_token_hash=? for update",
                (rs, n) -> new RegistrationRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("status_token_hash"), parse(rs.getString("onboarding_data")),
                        rs.getInt("last_completed_step"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id, hash)
                .stream().findFirst().orElseThrow(() -> error("DRAFT_NOT_FOUND"));
    }

    private OrganizationRegistrationDraftModels.Draft read(UUID id, String rawToken) {
        if (id == null || rawToken == null || rawToken.isBlank()) throw error("DRAFT_NOT_FOUND");
        return readByHash(id, tokens.sha256(rawToken));
    }

    private OrganizationRegistrationDraftModels.Draft readByHash(UUID id, String hash) {
        return jdbc.query("select id,status,status_token_hash,onboarding_data::text,last_completed_step,version,created_at,updated_at from tenant_management.organization_registration where id=? and status_token_hash=?",
                (rs, n) -> view(new RegistrationRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("status_token_hash"), parse(rs.getString("onboarding_data")),
                        rs.getInt("last_completed_step"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant())), id, hash)
                .stream().findFirst().orElseThrow(() -> error("DRAFT_NOT_FOUND"));
    }

    private IdempotencyRow idempotency(UUID id, String key) {
        return jdbc.query("select request_hash,operation,version_after from tenant_management.organization_registration_draft_idempotency where registration_id=? and idempotency_key=?",
                (rs, n) -> new IdempotencyRow(rs.getString("request_hash"), rs.getString("operation"), rs.getLong("version_after")), id, key.trim())
                .stream().findFirst().orElse(null);
    }

    private void remember(UUID id, String key, String operation, String hash, long version, Instant now) {
        jdbc.update("insert into tenant_management.organization_registration_draft_idempotency (registration_id,idempotency_key,operation,request_hash,version_after,created_at) values (?,?,?,?,?,?)",
                id, key.trim(), operation, hash, version, Timestamp.from(now));
    }

    private void ensureDraft(RegistrationRow row) { if (!"DRAFT".equals(row.status())) throw error("DRAFT_NOT_EDITABLE"); }
    private void ensureVersion(RegistrationRow row, int expected) { if (row.version() != expected) throw error("DRAFT_VERSION_CONFLICT"); }
    private void requireIdempotencyKey(String key) { if (key == null || key.isBlank() || key.length() > 128) throw error("IDEMPOTENCY_KEY_REQUIRED"); }

    private void validateStep(int step, Map<String, Object> values) {
        if (step < 1 || step > 6 || values == null || values.isEmpty()) throw error("DRAFT_STEP_INVALID");
        for (String key : values.keySet()) {
            String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (SECRET_KEYS.stream().anyMatch(normalized::contains)) throw error("DRAFT_SECRET_FIELD_NOT_ALLOWED");
            if (step == 5 && FORBIDDEN_COMMERCIAL_KEYS.stream().anyMatch(normalized::contains)) throw error("DRAFT_REFERENCE_METADATA_ONLY");
        }
        if (step == 1 && (blank(values, "legalName") || blank(values, "displayName"))) throw error("DRAFT_STEP_INVALID");
        if (step == 2 && (blank(values, "workspaceName") || blank(values, "workspaceSlug") || blank(values, "storageSiteName") || blank(values, "storageSiteAddress"))) throw error("DRAFT_STEP_INVALID");
        if (step == 3 && blank(values, "operationCategory")) throw error("DRAFT_STEP_INVALID");
        if (step == 4 && (blank(values, "founderEmail") || blank(values, "founderDisplayName"))) throw error("DRAFT_STEP_INVALID");
        if (step == 5 && (blank(values, "referencePlan") || !PLANS.contains(text(values, "referencePlan")))) throw error("DRAFT_STEP_INVALID");
        if (step == 6 && (blank(values, "termsVersion") || !Boolean.TRUE.equals(values.get("termsAccepted")))) throw error("DRAFT_TERMS_REQUIRED");
    }

    private void validateComplete(int lastStep, Map<String, Object> all) {
        if (lastStep < 6 || blank(all, "legalName") || blank(all, "displayName") || blank(all, "workspaceName") || blank(all, "workspaceSlug")
                || blank(all, "storageSiteName") || blank(all, "storageSiteAddress") || blank(all, "operationCategory")
                || blank(all, "founderEmail") || blank(all, "founderDisplayName") || !PLANS.contains(text(all, "referencePlan"))
                || blank(all, "termsVersion") || !Boolean.TRUE.equals(all.get("termsAccepted"))) throw error("DRAFT_INCOMPLETE");
        if (!text(all, "workspaceSlug").matches("[A-Za-z0-9-]{3,80}") || !text(all, "founderEmail").contains("@")) throw error("DRAFT_STEP_INVALID");
    }

    private static Map<String, Object> flatten(Map<String, Object> data) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (int step = 1; step <= 6; step++) {
            Object value = data.get("step" + step);
            if (value instanceof Map<?, ?> map) map.forEach((key, val) -> { if (key != null) all.put(String.valueOf(key), val); });
        }
        return all;
    }

    private static boolean blank(Map<String, Object> values, String key) { return text(values, key).isBlank(); }
    private static String text(Map<String, Object> values, String key) { Object value = values.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT); }
    private static boolean acceptedAt(Map<String, Object> values) { return Boolean.TRUE.equals(values.get("termsAccepted")); }
    private Map<String, Object> safeCopy(Map<String, Object> values) { return mapper.convertValue(values, new TypeReference<>() { }); }
    private Map<String, Object> parse(String raw) { try { return raw == null ? Map.of() : mapper.readValue(raw, new TypeReference<>() { }); } catch (JacksonException e) { throw new IllegalStateException("Onboarding draft JSON is invalid", e); } }
    private String json(Map<String, Object> values) { try { return mapper.writeValueAsString(values); } catch (JacksonException e) { throw error("DRAFT_STEP_INVALID"); } }
    private String canonical(Map<String, Object> value) { return json(new java.util.TreeMap<>(value)); }
    private static String requestHash(String operation, String payload) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((operation + "|" + payload).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("SHA-256 is required", e); } }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static OrganizationRegistrationDraftException error(String code) { return new OrganizationRegistrationDraftException(code); }
    private OrganizationRegistrationDraftModels.Draft view(RegistrationRow row) { return new OrganizationRegistrationDraftModels.Draft(row.id(), row.status(), row.lastCompletedStep(), completed(row.lastCompletedStep()), row.data(), row.version(), row.createdAt(), row.updatedAt()); }
    private static Set<Integer> completed(int last) { Set<Integer> result = new LinkedHashSet<>(); for (int i = 1; i <= Math.min(6, last); i++) result.add(i); return result; }
    private record RegistrationRow(UUID id, String status, String tokenHash, Map<String, Object> data, int lastCompletedStep, long version, Instant createdAt, Instant updatedAt) { }
    private record IdempotencyRow(String requestHash, String operation, long versionAfter) { }
}
