package com.nexa.api.tenantmanagement.domain.model.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationRegistrationTests {
    private OrganizationRegistration pending() { return OrganizationRegistration.submit(new OrganizationRegistrationId(UUID.randomUUID()), new FounderIdentity("Founder@Example.com", "Founder"), new WorkspaceSlug("icisa-test"), new TermsAcceptance("v1", true), ReferencePlan.Starter, new RegistrationStatusTokenHash("b".repeat(64))); }
    @Test void submissionIsPendingAndTypedValuesAreNormalized() { var value = pending(); assertThat(value.status()).isEqualTo(OrganizationRegistrationStatus.PENDING_ACTIVATION); assertThat(value.founder().email()).isEqualTo("founder@example.com"); assertThat(value.workspaceSlug().value()).isEqualTo("icisa-test"); }
    @Test void activationAndRejectionAreSingleTransitions() { var active = pending(); active.activate(); assertThat(active.status()).isEqualTo(OrganizationRegistrationStatus.ACTIVE); assertThatThrownBy(active::reject).isInstanceOf(IllegalStateException.class); var rejected = pending(); rejected.reject(); assertThat(rejected.status()).isEqualTo(OrganizationRegistrationStatus.REJECTED); assertThatThrownBy(rejected::activate).isInstanceOf(IllegalStateException.class); }
    @Test void termsFounderAndSlugInvariantsAreEnforced() { assertThatThrownBy(() -> new TermsAcceptance("v1", false)).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(() -> new FounderIdentity("bad", "Founder")).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(() -> new WorkspaceSlug("x")).isInstanceOf(IllegalArgumentException.class); }
    @Test void persistedActiveStateCanBeRehydratedButSubmissionCannotChooseIt() { var restored = OrganizationRegistration.restore(new OrganizationRegistrationId(UUID.randomUUID()), new FounderIdentity("a@example.com", "A"), new WorkspaceSlug("abc"), new TermsAcceptance("v1", true), ReferencePlan.Starter, new RegistrationStatusTokenHash("c".repeat(64)), OrganizationRegistrationStatus.ACTIVE); assertThat(restored.status()).isEqualTo(OrganizationRegistrationStatus.ACTIVE); assertThat(pending().status()).isEqualTo(OrganizationRegistrationStatus.PENDING_ACTIVATION); }
}
