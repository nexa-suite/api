package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.GetOwnProfileQuery;
import com.nexa.api.tenantaccessgovernance.iam.application.port.in.UpdateOwnProfileCommand;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserProfilePersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class OwnProfileService implements GetOwnProfileQuery, UpdateOwnProfileCommand {
    private final UserProfilePersistencePort profiles;
    private final SecurityAuditPort audit;
    private final Clock clock;

    public OwnProfileService(UserProfilePersistencePort profiles, SecurityAuditPort audit, Clock clock) {
        this.profiles = profiles; this.audit = audit; this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Profile get(Actor actor) { return profiles.findOwnProfile(Objects.requireNonNull(actor, "Verified actor is required")); }

    @Override
    @Transactional
    public Profile update(Actor actor, ProfilePatch patch) {
        Objects.requireNonNull(actor, "Verified actor is required");
        ProfilePatch normalized = validateAndNormalize(patch);
        Profile updated = profiles.updateOwn(actor, normalized);
        audit.append(new SecurityAuditPort.Event("PROFILE_UPDATED", actor.userId(), actor.userId(), actor.tenantId(), actor.workspaceId(),
                actor.surface(), value(actor.correlationId()), value(actor.traceId()), clock.instant(), Map.of("version", updated.version())));
        return updated;
    }

    private static ProfilePatch validateAndNormalize(ProfilePatch patch) {
        if (patch == null || patch.displayName() == null || patch.displayName().isBlank() || patch.displayName().trim().length() > 160
                || patch.preferredLanguage() == null || !java.util.Set.of("es", "en").contains(patch.preferredLanguage())
                || patch.timezone() == null || !validTimezone(patch.timezone())
                || patch.phone() != null && !patch.phone().isBlank() && !patch.phone().matches("\\+[1-9][0-9]{6,14}")) {
            throw new IamSecurityException("PROFILE_INVALID");
        }
        String firstName = optionalName(patch.firstName(), patch.firstNameProvided());
        String lastName = optionalName(patch.lastName(), patch.lastNameProvided());

        boolean anyDocumentField = patch.documentTypeProvided() || patch.documentNumberProvided()
                || patch.documentCountryCodeProvided();
        String documentType = patch.documentType();
        String documentNumber = patch.documentNumber();
        String documentCountryCode = patch.documentCountryCode();
        if (anyDocumentField) {
            if (!patch.documentTypeProvided() || !patch.documentNumberProvided() || !patch.documentCountryCodeProvided()) {
                throw new IamSecurityException("PROFILE_INVALID");
            }
            boolean allCleared = documentType == null && documentNumber == null && documentCountryCode == null;
            if (!allCleared) {
                documentType = normalizedRequired(documentType).toUpperCase(Locale.ROOT);
                documentNumber = normalizedRequired(documentNumber).toUpperCase(Locale.ROOT);
                documentCountryCode = normalizedRequired(documentCountryCode).toUpperCase(Locale.ROOT);
                if (!java.util.Set.of("DNI", "CE", "PASSPORT", "OTHER").contains(documentType)
                        || !documentNumber.matches("[A-Z0-9]{1,32}")
                        || !documentCountryCode.matches("[A-Z]{2}")
                        || "DNI".equals(documentType) && "PE".equals(documentCountryCode)
                                && !documentNumber.matches("[0-9]{8}")) {
                    throw new IamSecurityException("PROFILE_INVALID");
                }
            }
        }
        return new ProfilePatch(patch.displayName().trim(), patch.phone() == null || patch.phone().isBlank() ? null : patch.phone().trim(),
                patch.preferredLanguage(), patch.timezone(), firstName, patch.firstNameProvided(), lastName, patch.lastNameProvided(),
                documentType, patch.documentTypeProvided(), documentNumber, patch.documentNumberProvided(),
                documentCountryCode, patch.documentCountryCodeProvided(), patch.version());
    }

    private static String optionalName(String value, boolean provided) {
        if (!provided) return value;
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) throw new IamSecurityException("PROFILE_INVALID");
        return normalized;
    }

    private static String normalizedRequired(String value) {
        if (value == null || value.isBlank()) throw new IamSecurityException("PROFILE_INVALID");
        return value.trim();
    }

    private static boolean validTimezone(String value) {
        try { ZoneId.of(value); return value.contains("/") || "UTC".equals(value); }
        catch (RuntimeException exception) { return false; }
    }

    private static String value(String text) { return text == null || text.isBlank() ? "unknown" : text; }
}
