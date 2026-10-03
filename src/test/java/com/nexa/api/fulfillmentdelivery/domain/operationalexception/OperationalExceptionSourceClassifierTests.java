package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentSeverity;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalExceptionSourceClassifierTests {
    @Test
    void classifiesOnlyOwnerApprovedDispatchFacts() {
        assertThat(OperationalExceptionSourceClassifier.classify(IncidentType.DELAY, IncidentSeverity.LOW))
                .contains(OperationalExceptionSeverity.WARNING);
        assertThat(OperationalExceptionSourceClassifier.classify(IncidentType.TEMPERATURE_EXCURSION,
                IncidentSeverity.CRITICAL)).contains(OperationalExceptionSeverity.CRITICAL);
        assertThat(OperationalExceptionSourceClassifier.classify(IncidentType.TEMPERATURE_EXCURSION,
                IncidentSeverity.HIGH)).isEmpty();
        assertThat(OperationalExceptionSourceClassifier.classify(IncidentType.VEHICLE_ISSUE,
                IncidentSeverity.CRITICAL)).isEmpty();
    }

    @Test
    void derivesEveryDriverSeverityFromTheExplicitType() {
        Map<DriverDeliveryIncidentType, OperationalExceptionSeverity> expected = Map.of(
                DriverDeliveryIncidentType.DELAY, OperationalExceptionSeverity.WARNING,
                DriverDeliveryIncidentType.INCOMPLETE_INSTRUCTION, OperationalExceptionSeverity.WARNING,
                DriverDeliveryIncidentType.ACCESS_BLOCKED, OperationalExceptionSeverity.BLOCKING,
                DriverDeliveryIncidentType.CUSTOMER_UNAVAILABLE, OperationalExceptionSeverity.BLOCKING,
                DriverDeliveryIncidentType.DELIVERY_NOT_EXECUTABLE, OperationalExceptionSeverity.BLOCKING,
                DriverDeliveryIncidentType.TEMPERATURE_EXCURSION, OperationalExceptionSeverity.CRITICAL,
                DriverDeliveryIncidentType.SAFETY_COMPROMISING_DAMAGE, OperationalExceptionSeverity.CRITICAL);
        expected.forEach((type, severity) ->
                assertThat(OperationalExceptionSourceClassifier.classify(type)).isEqualTo(severity));
    }
}
