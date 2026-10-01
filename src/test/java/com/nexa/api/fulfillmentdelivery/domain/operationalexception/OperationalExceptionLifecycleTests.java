package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalExceptionLifecycleTests {
    private final UUID driver = UUID.randomUUID();
    private final UUID otherDriver = UUID.randomUUID();

    @Test
    void claimRequiresOpenAndUnassignedCase() {
        assertThat(OperationalExceptionLifecycle.claim(OperationalExceptionStatus.OPEN, null, driver))
                .hasValueSatisfying(transition -> {
                    assertThat(transition.status()).isEqualTo(OperationalExceptionStatus.CLAIMED);
                    assertThat(transition.actorMembershipId()).isEqualTo(driver);
                    assertThat(transition.responsibleMembershipId()).isEqualTo(driver);
                });
        assertThat(OperationalExceptionLifecycle.claim(OperationalExceptionStatus.CLAIMED, driver, otherDriver))
                .isEmpty();
        assertThat(OperationalExceptionLifecycle.claim(OperationalExceptionStatus.OPEN, otherDriver, driver))
                .isEmpty();
    }

    @Test
    void reviewRequiresTheCurrentResponsibleDriverAndCannotResolveCase() {
        assertThat(OperationalExceptionLifecycle.review(OperationalExceptionStatus.CLAIMED, driver, driver))
                .hasValueSatisfying(transition -> {
                    assertThat(transition.status()).isEqualTo(OperationalExceptionStatus.UNDER_REVIEW);
                    assertThat(transition.responsibleMembershipId()).isEqualTo(driver);
                });
        assertThat(OperationalExceptionLifecycle.review(OperationalExceptionStatus.CLAIMED, driver, otherDriver))
                .isEmpty();
        assertThat(OperationalExceptionLifecycle.review(OperationalExceptionStatus.OPEN, null, driver))
                .isEmpty();
    }
}
