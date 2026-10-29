package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserProfilePersistencePort;
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
        return jdbc.queryForObject("select id,email,display_name,phone,preferred_language,timezone,first_name,last_name,document_type,document_number,document_country_code,version from iam.user_account where id=?",
                (rs, row) -> profile(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("phone"), rs.getString("preferred_language"), rs.getString("timezone"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("document_type"), rs.getString("document_number"),
                        rs.getString("document_country_code"), rs.getLong("version")), actor.userId());
    }

    @Override public Profile updateOwn(Actor actor, ProfilePatch patch) {
        int updated = jdbc.update("update iam.user_account set display_name=?,phone=?,preferred_language=?,timezone=?,"
                        + "first_name=case when ? then ? else first_name end,last_name=case when ? then ? else last_name end,"
                        + "document_type=case when ? then ? else document_type end,document_number=case when ? then ? else document_number end,"
                        + "document_country_code=case when ? then ? else document_country_code end,updated_at=now(),version=version+1 where id=? and version=?",
                patch.displayName().trim(), patch.phone() == null || patch.phone().isBlank() ? null : patch.phone().trim(),
                patch.preferredLanguage(), patch.timezone(), patch.firstNameProvided(), patch.firstName(), patch.lastNameProvided(), patch.lastName(),
                patch.documentTypeProvided(), patch.documentType(), patch.documentNumberProvided(), patch.documentNumber(),
                patch.documentCountryCodeProvided(), patch.documentCountryCode(), actor.userId(), patch.version());
        if (updated != 1) throw new IamSecurityException("PROFILE_VERSION_CONFLICT");
        return findOwnProfile(actor);
    }

    private static Profile profile(UUID id, String email, String displayName, String phone, String language, String timezone,
            String firstName, String lastName, String documentType, String documentNumber, String documentCountryCode, long version) {
        return new Profile(id, email, displayName, phone, language, timezone, firstName, lastName, documentType, documentNumber,
                documentCountryCode, version);
    }
}
