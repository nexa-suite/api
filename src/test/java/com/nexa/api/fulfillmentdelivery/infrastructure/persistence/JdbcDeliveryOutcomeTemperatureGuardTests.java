package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.TemperatureView;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.TemperatureRequest;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDeliveryOutcomeTemperatureGuardTests {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DELIVERY = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID FULFILLMENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID LOT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID EVIDENCE = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Test
    void excursionIsRejectedBeforeAnyDurableWrite() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.adapter().recordTemperature(request("5")))
                .isInstanceOf(FulfillmentOperationException.class)
                .hasMessage("TEMPERATURE_OUT_OF_RANGE_BACKEND_CONTRACT_GAP");

        verify(fixture.jdbc(), never()).update(anyString(), any(Object[].class));
    }

    @Test
    void inRangeReadingContinuesThroughExistingPersistencePath() throws Exception {
        Fixture fixture = fixture();
        when(fixture.jdbc().update(anyString(), any(Object[].class))).thenReturn(1);

        TemperatureView result = fixture.adapter().recordTemperature(request("2"));

        assertThat(result.status()).isEqualTo("WITHIN_RANGE");
        verify(fixture.jdbc()).update(eq("insert into logistics.temperature_evidence(id,tenant_id,workspace_id,delivery_id,lot_id,value,temperature_celsius,unit,recorded_at,source,evidence_metadata,status,evidence_object_id,actor_membership_id,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"), any(Object[].class));
    }

    private static Fixture fixture() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ColdChainPolicyQuery coldChain = mock(ColdChainPolicyQuery.class);
        doAnswer(invocation -> null).when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            if (sql.contains("delivery_command_idempotency")) return List.of();
            if (sql.contains("from logistics.delivery d where")) return List.of(mapper.mapRow(deliveryRow(), 0));
            if (sql.contains("from logistics.temperature_evidence t")) return List.of(mapper.mapRow(temperatureRow(), 0));
            throw new AssertionError("Unexpected query in focused temperature test");
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(coldChain.lotIsAllocatedToDelivery(TENANT, WORKSPACE, DELIVERY, LOT)).thenReturn(true);
        when(coldChain.rangeForDeliveryAndLot(TENANT, WORKSPACE, DELIVERY, LOT)).thenReturn(
                Optional.of(new ColdChainPolicyQuery.Range(BigDecimal.ZERO, BigDecimal.valueOf(4), "CELSIUS")));
        var adapter = new JdbcDeliveryOutcomeAdapter(jdbc, coldChain, mock(ObjectMapper.class),
                Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC), mock(CanonicalOutboxPort.class));
        return new Fixture(jdbc, adapter);
    }

    private static ResultSet deliveryRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(DELIVERY);
        when(row.getObject("fulfillment_id", UUID.class)).thenReturn(FULFILLMENT);
        when(row.getObject("sales_order_id", UUID.class)).thenReturn(null);
        when(row.getString("status")).thenReturn("IN_TRANSIT");
        when(row.getString("destination_snapshot")).thenReturn("destination");
        when(row.getTimestamp(anyString())).thenReturn(null);
        when(row.getLong("version")).thenReturn(7L);
        return row;
    }

    private static ResultSet temperatureRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(EVIDENCE);
        when(row.getObject("delivery_id", UUID.class)).thenReturn(DELIVERY);
        when(row.getObject("lot_id", UUID.class)).thenReturn(LOT);
        when(row.getBigDecimal("temperature_celsius")).thenReturn(BigDecimal.valueOf(2));
        when(row.getString("unit")).thenReturn("CELSIUS");
        when(row.getString("source")).thenReturn("MANUAL");
        when(row.getString("status")).thenReturn("WITHIN_RANGE");
        when(row.getTimestamp(anyString())).thenReturn(null);
        when(row.getObject("delivery_version")).thenReturn(8L);
        return row;
    }

    private static TemperatureRequest request(String temperature) {
        return new TemperatureRequest(TENANT, WORKSPACE, DELIVERY, LOT, ACTOR, "temperature-test-key",
                "temperature-test-hash", new BigDecimal(temperature), "CELSIUS", "MANUAL", null,
                Instant.parse("2026-10-01T12:00:00Z"), 7L);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcDeliveryOutcomeAdapter adapter) { }
}
