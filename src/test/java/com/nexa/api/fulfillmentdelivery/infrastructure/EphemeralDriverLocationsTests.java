package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.EphemeralDriverLocations;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.Coordinate;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.SampleCommand;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class EphemeralDriverLocationsTests {
    @Test void limitsVisibilityToScopeAndExpiresExactlyAtTwoMinutes() {
        var store = new EphemeralDriverLocations();
        var now = Instant.parse("2026-11-01T12:00:00Z");
        var tenant=UUID.randomUUID(); var workspace=UUID.randomUUID(); var driver=UUID.randomUUID();
        var workday=UUID.randomUUID(); var delivery=UUID.randomUUID();
        var command = new SampleCommand(tenant,workspace,driver,workday,
                new Coordinate(UUID.randomUUID(),-12,-77,5,now,null),now);
        var sample=store.capture(command, Set.of(delivery));
        assertThat(sample.expiresAt()).isEqualTo(now.plusSeconds(120));
        assertThat(store.capture(command, Set.of(delivery))).isEqualTo(sample);
        assertThat(store.latest(tenant,workspace,driver,now).deliveries()).containsExactly(delivery);
        assertThat(store.latest(tenant,UUID.randomUUID(),driver,now)).isNull();
        assertThat(store.latest(tenant,workspace,driver,now.plusSeconds(120))).isNull();
        assertThat(new EphemeralDriverLocations().latest(tenant,workspace,driver,now)).isNull();
    }
    @Test void rejectsChangedIdempotencyPayloadAndDoesNotReplaceNewerSample() {
        var store = new EphemeralDriverLocations(); var now=Instant.parse("2026-11-01T12:00:00Z");
        var tenant=UUID.randomUUID(); var workspace=UUID.randomUUID(); var driver=UUID.randomUUID();
        var day=UUID.randomUUID(); var id=UUID.randomUUID(); var delivery=UUID.randomUUID();
        var first=new SampleCommand(tenant,workspace,driver,day,new Coordinate(id,1,2,3,now,null),now);
        store.capture(first,Set.of(delivery));
        var conflict=new SampleCommand(tenant,workspace,driver,day,new Coordinate(id,2,2,3,now,null),now);
        assertThatThrownBy(() -> store.capture(conflict,Set.of(delivery)))
                .isInstanceOf(FulfillmentOperationException.class).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
        var older=new SampleCommand(tenant,workspace,driver,day,
                new Coordinate(UUID.randomUUID(),3,2,3,now.minusSeconds(1),null),now);
        store.capture(older,Set.of(delivery));
        assertThat(store.latest(tenant,workspace,driver,now).coordinate().sampleId()).isEqualTo(id);
        store.clear(tenant,workspace,driver);
        assertThat(store.latest(tenant,workspace,driver,now)).isNull();
    }
    @Test void scheduledSweepRemovesExpiredSamplesWithoutTouchingFreshOnes() {
        var store = new EphemeralDriverLocations(); var now=Instant.now();
        var tenant=UUID.randomUUID(); var workspace=UUID.randomUUID();
        var oldActor=UUID.randomUUID(); var newActor=UUID.randomUUID(); var day=UUID.randomUUID();
        var oldTime=now.minusSeconds(121);
        store.capture(new SampleCommand(tenant,workspace,newActor,day,
                new Coordinate(UUID.randomUUID(),1,2,3,now,null),now), Set.of(UUID.randomUUID()));
        store.capture(new SampleCommand(tenant,workspace,oldActor,day,
                new Coordinate(UUID.randomUUID(),1,2,3,oldTime,null),oldTime), Set.of(UUID.randomUUID()));
        store.discardExpired();
        assertThat(store.latest(tenant,workspace,newActor,now)).isNotNull();
        assertThat(store.latest(tenant,workspace,oldActor,oldTime)).isNull();
    }
}
