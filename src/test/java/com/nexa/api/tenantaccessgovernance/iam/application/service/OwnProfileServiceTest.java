package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.exception.IamSecurityException;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserProfilePersistencePort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnProfileServiceTest {
    private static final Actor ACTOR = new Actor(UUID.randomUUID(), UUID.randomUUID(), "PLATFORM", null, null, "corr", "trace");

    @Test
    void normalizesOptionalHumanIdentityFieldsAndAuditsOnlyVersion() {
        AtomicReference<ProfilePatch> saved = new AtomicReference<>();
        AtomicReference<SecurityAuditPort.Event> event = new AtomicReference<>();
        OwnProfileService service = service(saved, event);

        service.update(ACTOR, patch(" María ", " López ", "dni", "12345678", "pe", true, true, true, 4));

        assertThat(saved.get().firstName()).isEqualTo("María");
        assertThat(saved.get().lastName()).isEqualTo("López");
        assertThat(saved.get().documentType()).isEqualTo("DNI");
        assertThat(saved.get().documentNumber()).isEqualTo("12345678");
        assertThat(saved.get().documentCountryCode()).isEqualTo("PE");
        assertThat(event.get().metadata()).containsOnlyKeys("version").containsEntry("version", 5L);
    }

    @Test
    void acceptsExplicitClearAndLegacyOmissionAsDifferentUpdates() {
        AtomicReference<ProfilePatch> saved = new AtomicReference<>();
        OwnProfileService service = service(saved, new AtomicReference<>());

        service.update(ACTOR, patch(null, null, null, null, null, true, true, true, 4));
        assertThat(saved.get().documentTypeProvided()).isTrue();
        assertThat(saved.get().documentType()).isNull();

        service.update(ACTOR, new ProfilePatch("Owner", null, "es", "UTC", null, false, null, false,
                null, false, null, false, null, false, 5));
        assertThat(saved.get().documentTypeProvided()).isFalse();
        assertThat(saved.get().documentNumberProvided()).isFalse();
        assertThat(saved.get().documentCountryCodeProvided()).isFalse();
    }

    @Test
    void rejectsIncompleteUnsupportedOrMalformedDocumentDetails() {
        assertInvalid(patch(null, null, "DNI", "12345678", null, true, false, true, 4));
        assertInvalid(patch(null, null, "RUC", "20123456789", "PE", true, true, true, 4));
        assertInvalid(patch(null, null, "DNI", "1234567A", "PE", true, true, true, 4));
        assertInvalid(patch(null, null, "PASSPORT", "ab-123", "US", true, true, true, 4));
        assertInvalid(patch(" ", null, null, null, null, true, true, true, 4));
    }

    private static void assertInvalid(ProfilePatch patch) {
        OwnProfileService service = service(new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> service.update(ACTOR, patch))
                .isInstanceOf(IamSecurityException.class)
                .hasMessage("PROFILE_INVALID");
    }

    private static OwnProfileService service(AtomicReference<ProfilePatch> saved, AtomicReference<SecurityAuditPort.Event> event) {
        UserProfilePersistencePort persistence = new UserProfilePersistencePort() {
            @Override public Profile findOwnProfile(Actor actor) { throw new UnsupportedOperationException(); }
            @Override public Profile updateOwn(Actor actor, ProfilePatch patch) {
                saved.set(patch);
                return new Profile(actor.userId(), "owner@example.invalid", patch.displayName(), patch.phone(),
                        patch.preferredLanguage(), patch.timezone(), patch.firstName(), patch.lastName(), patch.documentType(),
                        patch.documentNumber(), patch.documentCountryCode(), patch.version() + 1);
            }
        };
        SecurityAuditPort audit = event::set;
        return new OwnProfileService(persistence, audit, Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC));
    }

    private static ProfilePatch patch(String firstName, String lastName, String documentType, String documentNumber,
            String country, boolean firstProvided, boolean lastProvided, boolean documentProvided, long version) {
        return new ProfilePatch("Owner", null, "es", "UTC", firstName, firstProvided, lastName, lastProvided,
                documentType, documentProvided, documentNumber, documentProvided, country, documentProvided, version);
    }
}
