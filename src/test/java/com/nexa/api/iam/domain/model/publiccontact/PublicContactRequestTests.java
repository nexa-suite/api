package com.nexa.api.iam.domain.model.publiccontact;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicContactRequestTests {
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void receivesTypedAndNormalizedPublicRequestWithoutTenantIdentity() {
        PublicContactRequest request = PublicContactRequest.receive(UUID.randomUUID(), " demo ",
                "  Elena Rios  ", " ELENA@EXAMPLE.COM ", "  Cold Chain  ",
                "We need a demo for our refrigerated distribution operation.", RECEIVED_AT);

        assertThat(request.type()).isEqualTo(PublicContactRequest.Type.DEMO);
        assertThat(request.fullName()).isEqualTo("Elena Rios");
        assertThat(request.email()).isEqualTo("elena@example.com");
        assertThat(request.companyName()).isEqualTo("Cold Chain");
        assertThat(request.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void rejectsInvalidTypeAndMessage() {
        assertThatThrownBy(() -> PublicContactRequest.receive(UUID.randomUUID(), "LEAD", "Elena Rios",
                "elena@example.com", null, "We need more information about Nexa.", RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicContactRequest.receive(UUID.randomUUID(), "CONTACT", "E",
                "elena@example.com", null, "Too short", RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
