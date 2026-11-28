package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.Coordinate;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.SampleCommand;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.EphemeralDriverLocations;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverTrackingAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class EphemeralDriverTrackingIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");
    @Test void captureNeverPersistsCoordinatesAndReassignmentRevokesVisibility() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        jdbc.execute("create schema logistics");
        jdbc.execute("create table logistics.driver_workday(id uuid,tenant_id uuid,workspace_id uuid,actor_membership_id uuid,version bigint,status text,started_at timestamptz,ended_at timestamptz)");
        jdbc.execute("create table logistics.delivery(id uuid,tenant_id uuid,workspace_id uuid,status text,dispatched_at timestamptz,created_at timestamptz)");
        jdbc.execute("create table logistics.delivery_assignment(tenant_id uuid,workspace_id uuid,delivery_id uuid,responsible_membership_id uuid,assigned_at timestamptz)");
        jdbc.execute("create table logistics.driver_coordinate(sample_id uuid)");
        UUID tenant=UUID.randomUUID(), workspace=UUID.randomUUID(), actor=UUID.randomUUID(), day=UUID.randomUUID(), delivery=UUID.randomUUID();
        Instant now=Instant.parse("2026-11-01T12:00:00Z");
        jdbc.update("insert into logistics.driver_workday values(?,?,?,?,0,'ACTIVE',?,null)",day,tenant,workspace,actor,Timestamp.from(now.minusSeconds(300)));
        jdbc.update("insert into logistics.delivery values(?,?,?,'IN_TRANSIT',?,?)",delivery,tenant,workspace,Timestamp.from(now.minusSeconds(200)),Timestamp.from(now.minusSeconds(200)));
        jdbc.update("insert into logistics.delivery_assignment values(?,?,?,?,?)",tenant,workspace,delivery,actor,Timestamp.from(now.minusSeconds(200)));
        var adapter=new JdbcDriverTrackingAdapter(jdbc,new EphemeralDriverLocations());
        var request=new SampleCommand(tenant,workspace,actor,day,new Coordinate(UUID.randomUUID(),-12,-77,5,now,null),now);
        var sample=adapter.capture(request);
        assertThat(adapter.latestForDelivery(tenant,workspace,actor,delivery,now)).isEqualTo(sample);
        assertThat(jdbc.queryForObject("select count(*) from logistics.driver_coordinate",Integer.class)).isZero();
        jdbc.update("update logistics.delivery_assignment set assigned_at=?",Timestamp.from(now.plusSeconds(1)));
        assertThat(adapter.latestForDelivery(tenant,workspace,actor,delivery,now.plusSeconds(1))).isNull();
        jdbc.update("update logistics.delivery_assignment set assigned_at=?,responsible_membership_id=?",Timestamp.from(now.minusSeconds(1)),UUID.randomUUID());
        assertThat(adapter.latestForDelivery(tenant,workspace,actor,delivery,now.plusSeconds(1))).isNull();
        assertThat(new JdbcDriverTrackingAdapter(jdbc,new EphemeralDriverLocations())
                .latestForDelivery(tenant,workspace,actor,delivery,now)).isNull();
    }
}
