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
    @Test
    void warningCompletionCannotResolveBlockingOrCriticalConditions() {
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, driver, "DELAY", "WARNING")).hasValueSatisfying(value ->
                assertThat(value.reasonCode()).isEqualTo("DRIVER_DELAY_ADDRESSED"));
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, driver, "INCOMPLETE_INSTRUCTION", "WARNING")).isPresent();
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, driver, "ACCESS_BLOCKED", "BLOCKING")).isEmpty();
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, driver, "TEMPERATURE_EXCURSION", "CRITICAL")).isEmpty();
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, otherDriver, "DELAY", "WARNING")).isEmpty();
        assertThat(OperationalExceptionLifecycle.resolveWarning(OperationalExceptionStatus.CLAIMED,
                driver, driver, "DELAY", "WARNING")).isEmpty();
    }

    @Test
    void warningClosureRequiresPriorResolutionAndCurrentResponsibleDriver() {
        assertThat(OperationalExceptionLifecycle.closeWarning(OperationalExceptionStatus.RESOLVED,
                driver, driver, "WARNING")).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(OperationalExceptionStatus.CLOSED));
        assertThat(OperationalExceptionLifecycle.closeWarning(OperationalExceptionStatus.UNDER_REVIEW,
                driver, driver, "WARNING")).isEmpty();
        assertThat(OperationalExceptionLifecycle.closeWarning(OperationalExceptionStatus.RESOLVED,
                driver, otherDriver, "WARNING")).isEmpty();
        assertThat(OperationalExceptionLifecycle.closeWarning(OperationalExceptionStatus.RESOLVED,
                driver, driver, "CRITICAL")).isEmpty();
    }

}
