package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.iam.application.port.out.UserProfilePersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcUserProfilePersistenceAdapter implements UserProfilePersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcUserProfilePersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Profile findOwnProfile(Actor actor) {
        return jdbc.queryForObject("select id,email,display_name,phone,preferred_language,timezone,version from iam.user_account where id=?",
                (rs, row) -> profile(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("phone"), rs.getString("preferred_language"), rs.getString("timezone"), rs.getLong("version")), actor.userId());
    }

    @Override public Profile updateOwn(Actor actor, ProfilePatch patch) {
        int updated = jdbc.update("update iam.user_account set display_name=?,phone=?,preferred_language=?,timezone=?,updated_at=now(),version=version+1 where id=? and version=?",
                patch.displayName().trim(), patch.phone() == null || patch.phone().isBlank() ? null : patch.phone().trim(),
                patch.preferredLanguage(), patch.timezone(), actor.userId(), patch.version());
        if (updated != 1) throw new IllegalStateException("PROFILE_VERSION_CONFLICT");
        return findOwnProfile(actor);
    }

    private static Profile profile(UUID id, String email, String displayName, String phone, String language, String timezone, long version) {
        return new Profile(id, email, displayName, phone, language, timezone, version);
    }
}
