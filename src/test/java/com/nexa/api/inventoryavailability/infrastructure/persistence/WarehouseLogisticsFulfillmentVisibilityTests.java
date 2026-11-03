package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort.ReservationOrderReference;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WarehouseLogisticsFulfillmentVisibilityTests {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WAREHOUSE_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID WAREHOUSE_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RESERVATION_A = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID RESERVATION_B = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESERVATION_C = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID ORDER_A = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID ORDER_B = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID ORDER_C = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID COMMITMENT_A = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID COMMITMENT_B = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void filtersOnlyReservationsWhoseCompleteBackingUsesGrantedWarehouses() throws Exception {
        Fixture fixture = fixture(Set.of(RESERVATION_A, RESERVATION_B, RESERVATION_C),
                Set.of(COMMITMENT_A), Set.of("BACKED", "CONSUMED"));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_A))
                .thenReturn(Optional.of(order(ORDER_A, COMMITMENT_A)));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_B))
                .thenReturn(Optional.of(order(ORDER_B, COMMITMENT_B)));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_C))
                .thenReturn(Optional.empty());

        Set<ReservationOrderReference> visible = fixture.adapter().visibleReservationReferences(
                TENANT.toString(), WORKSPACE.toString(),
                Set.of(ref(RESERVATION_A, ORDER_A), ref(RESERVATION_B, ORDER_B), ref(RESERVATION_C, ORDER_C)),
                Set.of(WAREHOUSE_A, WAREHOUSE_B));

        assertThat(visible).containsExactly(ref(RESERVATION_A, ORDER_A));
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_A);
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_B);
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_C);
    }

    @Test
    void emptyReservationOrGrantSetFailsClosedWithoutDatabaseAccess() {
        Fixture fixture = fixture(Set.of(), Set.of(), Set.of("BACKED", "CONSUMED"));

        assertThat(fixture.adapter().visibleReservationReferences(TENANT.toString(), WORKSPACE.toString(),
                Set.of(), Set.of(WAREHOUSE_A))).isEmpty();
        assertThat(fixture.adapter().visibleReservationReferences(TENANT.toString(), WORKSPACE.toString(),
                Set.of(ref(RESERVATION_A, ORDER_A)), Set.of())).isEmpty();

        verifyNoInteractions(fixture.jdbc(), fixture.commercialSource());
    }

    @Test
    void partialWarehouseGrantDoesNotExposeReservationBackedAcrossWarehouses() throws Exception {
        Fixture fixture = fixture(Set.of(RESERVATION_A), Set.of(), Set.of("BACKED", "CONSUMED"));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_A))
                .thenReturn(Optional.of(order(ORDER_A, COMMITMENT_A)));

        Set<ReservationOrderReference> visible = fixture.adapter().visibleReservationReferences(
                TENANT.toString(), WORKSPACE.toString(), Set.of(ref(RESERVATION_A, ORDER_A)), Set.of(WAREHOUSE_A));

        assertThat(visible).isEmpty();
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_A);
    }

    @Test
    void mismatchedExpectedSalesOrderFailsClosed() {
        Fixture fixture = fixture(Set.of(RESERVATION_A), Set.of(COMMITMENT_A), Set.of("BACKED", "CONSUMED"));

        assertThat(fixture.adapter().visibleReservationReferences(TENANT.toString(), WORKSPACE.toString(),
                Set.of(ref(RESERVATION_A, ORDER_B)), Set.of(WAREHOUSE_A))).isEmpty();

        verifyNoInteractions(fixture.commercialSource());
    }

    @Test
    void countReadyReservationsUsesSalesOwnerSnapshotAndWarehouseOnlySql() {
        Fixture fixture = fixture(Set.of(RESERVATION_A, RESERVATION_B), Set.of(COMMITMENT_A), Set.of("BACKED"));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_A))
                .thenReturn(Optional.of(order(ORDER_A, COMMITMENT_A)));
        when(fixture.commercialSource().findCandidate(TENANT, WORKSPACE, ORDER_B))
                .thenReturn(Optional.of(order(ORDER_B, COMMITMENT_B)));

        long count = fixture.adapter().countReadyReservations(TENANT.toString(), WORKSPACE.toString(),
                Set.of(WAREHOUSE_A), Instant.parse("2026-10-22T10:00:00Z"));

        assertThat(count).isEqualTo(1);
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_A);
        verify(fixture.commercialSource()).findCandidate(TENANT, WORKSPACE, ORDER_B);
    }

    private static Fixture fixture(Set<UUID> reservations, Set<UUID> eligibleCommitments,
                                   Set<String> expectedBackingStatuses) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InventoryCommercialSource commercialSource = mock(InventoryCommercialSource.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            assertThat(sql).doesNotContain("sales.sales_order");
            if (sql.contains("from warehouse.inventory_reservation r")) {
                if (sql.contains("r.status='RESERVED'")) {
                    assertThat(sql).contains("r.expires_at>?");
                }
                List<Object> rows = new ArrayList<>();
                int index = 0;
                for (UUID reservation : reservations.stream().sorted().toList()) {
                    UUID order = reservation.equals(RESERVATION_A) ? ORDER_A
                            : reservation.equals(RESERVATION_B) ? ORDER_B : ORDER_C;
                    rows.add(mapper.mapRow(reservationRow(reservation, order), index++));
                }
                return rows;
            }
            if (sql.contains("from warehouse.inventory_backing b")) {
                assertThat(sql).contains("warehouse.inventory_backing_line", "warehouse.inventory_backing_position",
                        "bp.warehouse_id not in (");
                Object[] args = queryArguments(invocation);
                assertThat(args).contains(WAREHOUSE_A);
                if (eligibleCommitments.isEmpty()) {
                    assertThat(sql).contains("bp.warehouse_id not in (?)");
                    assertThat(args).doesNotContain(WAREHOUSE_B);
                }
                assertThat(args).doesNotContain("sales.sales_order");
                assertThat(Arrays.asList(args)).containsAll(expectedBackingStatuses);
                List<Object> rows = new ArrayList<>();
                int index = 0;
                for (UUID commitment : eligibleCommitments.stream().sorted().toList()) {
                    rows.add(mapper.mapRow(commitmentRow(commitment), index++));
                }
                return rows;
            }
            throw new AssertionError("Unexpected Warehouse visibility query: " + sql);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        return new Fixture(jdbc, commercialSource,
                new WarehouseLogisticsFulfillmentAdapter(jdbc, mock(ChangeEventPersistencePort.class), commercialSource));
    }

    private static InventoryCommercialSource.Snapshot order(UUID id, UUID commitmentId) {
        return new InventoryCommercialSource.Snapshot(id, "SO-1", "CONFIRMED", 1, UUID.randomUUID(),
                commitmentId, "Destination", List.of());
    }

    private static ReservationOrderReference ref(UUID reservationId, UUID salesOrderId) {
        return new ReservationOrderReference(reservationId, salesOrderId);
    }

    private static Object[] queryArguments(InvocationOnMock invocation) {
        Object[] invocationArguments = invocation.getArguments();
        if (invocationArguments.length <= 2) return new Object[0];
        if (invocationArguments.length == 3 && invocationArguments[2] instanceof Object[] queryArguments) {
            return queryArguments;
        }
        return Arrays.copyOfRange(invocationArguments, 2, invocationArguments.length);
    }

    private static ResultSet reservationRow(UUID id, UUID salesOrderId) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(id);
        when(row.getObject("sales_order_id", UUID.class)).thenReturn(salesOrderId);
        return row;
    }

    private static ResultSet commitmentRow(UUID commitmentId) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("commercial_commitment_id", UUID.class)).thenReturn(commitmentId);
        return row;
    }

    private record Fixture(JdbcTemplate jdbc, InventoryCommercialSource commercialSource,
                           WarehouseLogisticsFulfillmentAdapter adapter) { }
}
