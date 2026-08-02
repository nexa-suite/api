package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.iam.application.port.in.GetOwnProfileQuery;
import com.nexa.api.iam.application.port.in.UpdateOwnProfileCommand;
import com.nexa.api.iam.application.port.out.UserProfilePersistencePort;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
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
        validate(patch);
        Profile updated = profiles.updateOwn(actor, patch);
        audit.append(new SecurityAuditPort.Event("PROFILE_UPDATED", actor.userId(), actor.userId(), actor.tenantId(), actor.workspaceId(),
                actor.surface(), value(actor.correlationId()), value(actor.traceId()), clock.instant(), Map.of("version", updated.version())));
        return updated;
    }

    private static void validate(ProfilePatch patch) {
        if (patch == null || patch.displayName() == null || patch.displayName().isBlank() || patch.displayName().trim().length() > 160
                || patch.preferredLanguage() == null || !java.util.Set.of("es", "en").contains(patch.preferredLanguage())
                || patch.timezone() == null || !validTimezone(patch.timezone())
                || patch.phone() != null && !patch.phone().isBlank() && !patch.phone().matches("\\+[1-9][0-9]{6,14}")) {
            throw new IamSecurityException("PROFILE_INVALID");
        }
    }

    private static boolean validTimezone(String value) {
        try { ZoneId.of(value); return value.contains("/") || "UTC".equals(value); }
        catch (RuntimeException exception) { return false; }
    }

    private static String value(String text) { return text == null || text.isBlank() ? "unknown" : text; }
}
