package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DispatchLifecycleIT extends NexaWorkflowIntegrationSupport {
    private record SalesOwnerState(UUID membershipId, long version, java.sql.Timestamp updatedAt) { }

    @Test void salesDispatchReadsFollowActiveAccountAssignment() throws Exception {
        var dispatch = createReservedDispatch();
        UUID accountId = tenantJdbc().queryForObject(
                "select client_account_id from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(dispatch.salesOrderId()));
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        SalesOwnerState originalOwner = tenantJdbc().query(
                "select sales_owner_membership_id,version,updated_at from sales.client_account "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next() ? new SalesOwnerState(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getTimestamp(3)) : null, tenant, workspace, accountId);
        assertThat(originalOwner).isNotNull();
        int cleared = tenantJdbc().update("update sales.client_account set sales_owner_membership_id=null, "
                        + "updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? "
                        + "and id=? and version=? and sales_owner_membership_id is not distinct from ?",
                tenant, workspace, accountId, originalOwner.version(), originalOwner.membershipId());
        assertThat(cleared).isEqualTo(1);
        long clearedVersion = originalOwner.version() + 1;
        UUID salesMembership = UUID.fromString(membershipId(SALES_EMAIL));
        try {
            String sales = accessToken(SALES_EMAIL, "PLATFORM");
            MvcResult unassignedList = mockMvc.perform(get("/api/v1/dispatch-orders")
                            .header("Authorization", "Bearer " + sales))
                    .andExpect(status().isOk()).andReturn();
            assertThat(StreamSupport.stream(json(unassignedList).get("items").spliterator(), false)
                    .map(item -> item.path("id").asText()).toList()).doesNotContain(dispatch.id());
            mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                            .header("Authorization", "Bearer " + sales))
                    .andExpect(status().isNotFound());

            String owner = accessToken(OWNER_EMAIL, "PLATFORM");
            MvcResult account = mockMvc.perform(get("/api/v1/client-accounts/" + accountId)
                            .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isOk()).andReturn();
            mockMvc.perform(put("/api/v1/client-accounts/" + accountId + "/sales-owner")
                            .header("Authorization", "Bearer " + owner)
                            .header("If-Match", account.getResponse().getHeader("ETag"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + salesMembership + "\"}"))
                    .andExpect(status().isOk());

            MvcResult assignedList = mockMvc.perform(get("/api/v1/dispatch-orders")
                            .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                    .andExpect(status().isOk()).andReturn();
            assertThat(StreamSupport.stream(json(assignedList).get("items").spliterator(), false)
                    .map(item -> item.path("id").asText()).toList()).contains(dispatch.id());
            mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                            .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id() + "/events")
                            .header("Authorization", "Bearer " + accessToken(SALES_EMAIL, "PLATFORM")))
                    .andExpect(status().isOk());
        } finally {
            SalesOwnerState currentOwner = tenantJdbc().query(
                    "select sales_owner_membership_id,version,updated_at from sales.client_account "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    rs -> rs.next() ? new SalesOwnerState(rs.getObject(1, UUID.class), rs.getLong(2),
                            rs.getTimestamp(3)) : null, tenant, workspace, accountId);
            if (currentOwner != null && (currentOwner.membershipId() == null
                    || currentOwner.membershipId().equals(salesMembership))) {
                tenantJdbc().update("update sales.client_account set sales_owner_membership_id=?,updated_at=?,version=? "
                                + "where tenant_id=? and workspace_id=? and id=? and version=? "
                                + "and sales_owner_membership_id is not distinct from ?",
                        originalOwner.membershipId(), originalOwner.updatedAt(), originalOwner.version(), tenant,
                        workspace, accountId, currentOwner.version(), currentOwner.membershipId());
            }
        }
    }

    @Test void dispatchNumbersRemainSequentialWithinTheYear() throws Exception {
        var first = createReservedDispatch();
        var second = createReservedDispatch();
        assertThat(first.dispatchNumber()).matches("DO-\\d{4}-\\d{6}");
        assertThat(second.dispatchNumber()).matches("DO-\\d{4}-\\d{6}");
        assertThat(second.dispatchNumber().substring(0, 7)).isEqualTo(first.dispatchNumber().substring(0, 7));
        int firstValue = Integer.parseInt(first.dispatchNumber().substring(8));
        int secondValue = Integer.parseInt(second.dispatchNumber().substring(8));
        assertThat(secondValue).isEqualTo(firstValue + 1);
    }

    @Test void routeStartConsumesReservationOnceAndDeliveryStoresPodMetadata() throws Exception {
        var dispatch = createReservedDispatch();
        dispatch = prepare(dispatch);
        dispatch = assign(dispatch);
        dispatch = schedule(dispatch);
        dispatch = ready(dispatch);
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-start-1");
        assertThat(json(detail(dispatch)).get("status").asText()).isEqualTo("IN_ROUTE");
        assertThat(tenantJdbc().queryForObject("select status from warehouse.inventory_reservation where id=?", String.class, java.util.UUID.fromString(dispatch.reservationId()))).isEqualTo("CONSUMED");
        assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where correlation_id=? and movement_type='OUTBOUND_CONSUMPTION'", Integer.class, dispatch.id())).isEqualTo(1);
        MvcResult pendingPod = mockMvc.perform(get("/api/v1/proof-of-delivery?status=PENDING").header("Authorization", "Bearer " + dispatch.logisticsToken())).andExpect(status().isOk()).andReturn();
        assertThat(json(pendingPod).toString()).contains(dispatch.id());
        MvcResult delivered = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/delivery-completions")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", dispatch.etag()).header("Idempotency-Key", "pod-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverName\":\"Carlos\",\"completedAt\":\"" + Instant.now() + "\",\"notes\":\"Received\",\"photoEvidenceDeclared\":false,\"signatureEvidenceDeclared\":true}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(delivered).get("status").asText()).isEqualTo("DELIVERED");
        MvcResult completedPod = mockMvc.perform(get("/api/v1/proof-of-delivery?status=COMPLETED").header("Authorization", "Bearer " + dispatch.logisticsToken())).andExpect(status().isOk()).andReturn();
        assertThat(json(completedPod).toString()).contains(dispatch.id());
        final String deliveredDispatchId = dispatch.id();
        assertThatThrownBy(() -> tenantJdbc().update("update logistics.proof_of_delivery set notes='tampered' where dispatch_order_id=?", java.util.UUID.fromString(deliveredDispatchId))).isInstanceOf(RuntimeException.class);
    }

    @Test void failedAttemptRemainsOnTheSameDeliveryAndIsIdempotent() throws Exception {
        var dispatch = ready(schedule(assign(prepare(createReservedDispatch()))));
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-failed-" + dispatch.id());
        String originalEtag = dispatch.etag();
        String body = "{\"failureReason\":\"Buyer unavailable\"}";

        mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/delivery-attempts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken())
                        .header("Idempotency-Key", "failed-attempt-missing-etag-" + dispatch.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());

        MvcResult first = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/delivery-attempts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", "failed-attempt-" + dispatch.id()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/delivery-attempts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", "failed-attempt-" + dispatch.id()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(first).get("status").asText()).isEqualTo("IN_ROUTE");
        assertThat(json(first).get("lastAttempt").get("status").asText()).isEqualTo("FAILED");
        assertThat(json(first).get("lastAttempt").get("failureReason").asText()).isEqualTo("Buyer unavailable");
        assertThat(json(replay).get("lastAttempt").get("id").asText())
                .isEqualTo(json(first).get("lastAttempt").get("id").asText());
        assertThat(replay.getResponse().getHeader("ETag")).isEqualTo(first.getResponse().getHeader("ETag"));
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?", Integer.class,
                java.util.UUID.fromString(dispatch.id()))).isEqualTo(1);

        mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/delivery-attempts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", "failed-attempt-competing-" + dispatch.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed());
    }

    @Test void partialDeliveryClosesTheDeliveryWithContinuationAndBuyerSafeProjection() throws Exception {
        var dispatch = ready(schedule(assign(prepare(createReservedDispatch()))));
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-partial-" + dispatch.id());
        String originalEtag = dispatch.etag();
        String key = "partial-delivery-" + dispatch.id();
        String body = "{\"catalogItemId\":\"CAT-0002\",\"deliveredQuantity\":0.5,\"unit\":\"UNIT\",\"notes\":\"Half delivered\"}";

        MvcResult first = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/partial-deliveries")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/partial-deliveries")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        assertThat(json(first).get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json(first).get("lastAttempt").get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json(first).get("lastAttempt").get("deliveredLines").get(0).get("quantity").decimalValue())
                .isEqualByComparingTo("0.5");
        assertThat(json(first).get("continuationDeliveryId").asText()).isNotBlank();
        assertThat(json(first).get("remainingObligation").get(0).get("quantity").decimalValue())
                .isEqualByComparingTo("0.5");
        assertThat(json(first).get("podId").isNull()).isTrue();
        assertThat(json(replay).get("id").asText()).isEqualTo(dispatch.id());
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.delivery_attempt where delivery_id=?", Integer.class,
                java.util.UUID.fromString(dispatch.id()))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select coalesce(sum(received_quantity),0) from logistics.delivery_attempt_line where delivery_attempt_id=(select id from logistics.delivery_attempt where delivery_id=? order by attempt_number desc limit 1)",
                BigDecimal.class, java.util.UUID.fromString(dispatch.id()))).isEqualByComparingTo("0.5");
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.continuation_delivery where source_delivery_id=?", Integer.class,
                java.util.UUID.fromString(dispatch.id()))).isEqualTo(1);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.proof_of_delivery where dispatch_order_id=?", Integer.class,
                java.util.UUID.fromString(dispatch.id()))).isEqualTo(0);
        MvcResult pendingPod = mockMvc.perform(get("/api/v1/proof-of-delivery?status=PENDING")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken())).andExpect(status().isOk()).andReturn();
        assertThat(json(pendingPod).toString()).doesNotContain(dispatch.id());

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult buyerView = mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                        .header("Authorization", "Bearer " + buyer)).andExpect(status().isOk()).andReturn();
        assertThat(json(buyerView).get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json(buyerView).get("reservationId").isNull()).isTrue();
        assertThat(json(buyerView).get("salesOrderId").isNull()).isTrue();
        assertThat(json(buyerView).get("clientAccountId").isNull()).isTrue();
        assertThat(json(buyerView).get("assignment").isNull()).isTrue();
        assertThat(json(buyerView).get("continuationDeliveryId").isNull()).isTrue();
        assertThat(json(buyerView).get("lastAttempt").get("id").isNull()).isTrue();
        assertThat(json(buyerView).get("lastAttempt").get("status").asText()).isEqualTo("DELIVERY_REVIEW");
        assertThat(json(buyerView).get("lastAttempt").get("failureReason").isNull()).isTrue();
        assertThat(json(buyerView).get("remainingObligation").get(0).get("quantity").decimalValue())
                .isEqualByComparingTo("0.5");

        mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/partial-deliveries")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", originalEtag)
                        .header("Idempotency-Key", "partial-delivery-competing-" + dispatch.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed());
    }

    @Test void temperatureExcursionCreatesIncidentAndBuyerSeesOnlyMappedReview() throws Exception {
        var dispatch = ready(schedule(assign(prepare(
                createReservedDispatch("CAT-0005", java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2))))));
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-temperature");
        MvcResult reading = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/temperature-readings")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", dispatch.etag()).header("Idempotency-Key", "temperature-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":-5,\"unit\":\"CELSIUS\",\"source\":\"MANUAL\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(reading).get("status").asText()).isEqualTo("INCIDENT");
        final String temperatureDispatchId = dispatch.id();
        assertThatThrownBy(() -> tenantJdbc().update("update logistics.temperature_reading set source='tampered' where dispatch_order_id=?", java.util.UUID.fromString(temperatureDispatchId))).isInstanceOf(RuntimeException.class);
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult events = mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id() + "/events").header("Authorization", "Bearer " + buyer)).andExpect(status().isOk()).andReturn();
        assertThat(json(events).toString()).contains("DELIVERY_REVIEW");
        assertThat(json(events).toString()).doesNotContain("Temperature excursion");
    }

    @Test void concurrentRouteStartWithSameKeyConsumesStockOnceAndReturnsSameResponse() throws Exception {
        var dispatch = ready(schedule(assign(prepare(createReservedDispatch()))));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> routeStart(dispatch, "route-concurrent"));
            Future<MvcResult> second = executor.submit(() -> routeStart(dispatch, "route-concurrent"));
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(firstResult).get("id").asText()).isEqualTo(json(secondResult).get("id").asText());
            assertThat(json(firstResult).get("status").asText()).isEqualTo("IN_ROUTE");
            assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.stock_movement where correlation_id=? and movement_type='OUTBOUND_CONSUMPTION'", Integer.class, dispatch.id())).isEqualTo(1);
            assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.inventory_event where correlation_id=? and event_type='OUTBOUND_CONSUMPTION'", Integer.class, dispatch.id())).isEqualTo(1);
            MvcResult competing = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/route-starts")
                            .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", dispatch.etag()).header("Idempotency-Key", "route-concurrent-other")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
            assertThat(competing.getResponse().getStatus()).isEqualTo(412);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void incidentReprogrammingAndRestartRemainWithinAggregateTransitions() throws Exception {
        var dispatch = ready(schedule(assign(prepare(createReservedDispatch()))));
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-incident");
        dispatch = mutate(dispatch, "/incidents", "{\"type\":\"DELAY\",\"severity\":\"MEDIUM\",\"buyerVisible\":true,\"description\":\"Traffic delay\"}", "incident-1");
        dispatch = reprogram(dispatch);
        dispatch = scheduleAfterReprogram(dispatch);
        dispatch = mutate(dispatch, "/route-readiness", "{}", "ready-after-reprogram-" + dispatch.id());
        dispatch = mutate(dispatch, "/route-starts", "{}", "route-reprogrammed");
        assertThat(json(detail(dispatch)).get("status").asText()).isEqualTo("IN_ROUTE");
    }

    @Test void cancellationReleasesReservationAndReservedStockAtomically() throws Exception {
        var dispatch = createReservedDispatch();
        java.util.UUID reservationId = java.util.UUID.fromString(dispatch.reservationId());
        java.math.BigDecimal reservedBefore = tenantJdbc().queryForObject("select coalesce(sum(l.reserved_quantity),0) from warehouse.inventory_lot l where l.id in (select lot_id from warehouse.inventory_reservation_allocation where reservation_line_id in (select id from warehouse.inventory_reservation_line where reservation_id=?))", java.math.BigDecimal.class, reservationId);
        java.math.BigDecimal allocated = tenantJdbc().queryForObject("select coalesce(sum(quantity),0) from warehouse.inventory_reservation_allocation where reservation_line_id in (select id from warehouse.inventory_reservation_line where reservation_id=?)", java.math.BigDecimal.class, reservationId);
        MvcResult cancelled = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/cancellations")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()).header("If-Match", dispatch.etag()).header("Idempotency-Key", "cancel-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer cancelled\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(tenantJdbc().queryForObject("select status from warehouse.inventory_reservation where id=?", String.class, reservationId)).isEqualTo("RELEASED");
        java.math.BigDecimal reservedAfter = tenantJdbc().queryForObject("select coalesce(sum(l.reserved_quantity),0) from warehouse.inventory_lot l where l.id in (select lot_id from warehouse.inventory_reservation_allocation where reservation_line_id in (select id from warehouse.inventory_reservation_line where reservation_id=?))", java.math.BigDecimal.class, reservationId);
        assertThat(reservedAfter).isEqualByComparingTo(reservedBefore.subtract(allocated));
    }

    @Test void buyerTrackingStripsInternalDispatchFieldsAndAnalyticsIsServerBacked() throws Exception {
        var dispatch = createReservedDispatch();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        MvcResult buyerView = mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id()).header("Authorization", "Bearer " + buyer)).andExpect(status().isOk()).andReturn();
        assertThat(json(buyerView).get("reservationId").isNull()).isTrue();
        assertThat(json(buyerView).get("assignment").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/logistics/operations-dashboard").header("Authorization", "Bearer " + dispatch.logisticsToken())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/logistics/operational-analytics").header("Authorization", "Bearer " + dispatch.logisticsToken())).andExpect(status().isOk());
    }

    @Test void staleAndMissingIfMatchAreControlledDispatchPreconditions() throws Exception {
        var dispatch = createReservedDispatch();
        MvcResult stale = mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/preparation-starts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken())
                        .header("If-Match", "\"999999\"")
                        .header("Idempotency-Key", "stale-etag-" + dispatch.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        mockMvc.perform(post("/api/v1/dispatch-orders/" + dispatch.id() + "/preparation-starts")
                        .header("Authorization", "Bearer " + dispatch.logisticsToken())
                        .header("Idempotency-Key", "missing-etag-" + dispatch.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isPreconditionRequired());
    }

    @Test void dispatchResponseCarriesPersistedBusinessCardFields() throws Exception {
        var dispatch = createReservedDispatch();
        MvcResult detail = mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                        .header("Authorization", "Bearer " + dispatch.logisticsToken()))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(detail).get("clientCode").asText()).isNotBlank();
        assertThat(json(detail).get("clientName").asText()).isNotBlank();
        assertThat(json(detail).get("priority").asText()).isIn("NORMAL", "HIGH", "URGENT");
        assertThat(json(detail).get("temperatureStatus").asText()).isNotBlank();
    }

    @Test void revokedWarehouseGrantDeniesHandoffReplayAndNewWriteWithoutChangingDispatch() throws Exception {
        var dispatch = createReservedDispatch();
        UUID commercialCommitmentId = tenantJdbc().queryForObject(
                "select commercial_commitment_id from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                UUID.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                UUID.fromString(dispatch.salesOrderId()));
        Set<UUID> backingWarehouseIds = Set.copyOf(tenantJdbc().query(
                "select distinct bp.warehouse_id from warehouse.inventory_backing b "
                        + "join warehouse.inventory_backing_line bl on bl.tenant_id=b.tenant_id "
                        + "and bl.workspace_id=b.workspace_id and bl.backing_id=b.id "
                        + "join warehouse.inventory_backing_position bp on bp.tenant_id=bl.tenant_id "
                        + "and bp.workspace_id=bl.workspace_id and bp.backing_line_id=bl.id "
                        + "where b.tenant_id=? and b.workspace_id=? and b.commercial_commitment_id=? "
                        + "and b.status in ('BACKED','CONSUMED') order by bp.warehouse_id",
                (rs, row) -> rs.getObject("warehouse_id", UUID.class), UUID.fromString(tenantId()),
                UUID.fromString(workspaceId()), commercialCommitmentId));
        assertThat(backingWarehouseIds).isNotEmpty();
        String warehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");
        String handoffPath = "/api/v1/dispatch-orders/" + dispatch.id() + "/handoff-notes";
        String key = "handoff-revoke-" + dispatch.id();
        String originalEtag = dispatch.etag();
        String body = "{\"note\":\"Carga preparada para transferencia\"}";

        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        for (UUID backingWarehouseId : backingWarehouseIds) {
            MvcResult grantsResult = mockMvc.perform(get("/api/v1/warehouses/" + backingWarehouseId + "/access-grants")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk()).andReturn();
            var grant = StreamSupport.stream(json(grantsResult).spliterator(), false)
                    .filter(value -> membershipId(WAREHOUSE_EMAIL).equals(value.path("membershipId").asText()))
                    .findFirst().orElseThrow();
            assertThat(grant.path("status").asText()).isEqualTo("ACTIVE");
        }

        MvcResult first = mockMvc.perform(post(handoffPath)
                        .header("Authorization", "Bearer " + warehouseToken)
                        .header("If-Match", originalEtag)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(first).get("dispatchOrderId").asText()).isEqualTo(dispatch.id());

        for (UUID backingWarehouseId : backingWarehouseIds) {
            MvcResult grantsResult = mockMvc.perform(get("/api/v1/warehouses/" + backingWarehouseId + "/access-grants")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk()).andReturn();
            var grant = StreamSupport.stream(json(grantsResult).spliterator(), false)
                    .filter(value -> membershipId(WAREHOUSE_EMAIL).equals(value.path("membershipId").asText()))
                    .findFirst().orElseThrow();
            assertThat(grant.path("status").asText()).isEqualTo("ACTIVE");
            mockMvc.perform(delete("/api/v1/warehouses/" + backingWarehouseId + "/access-grants/"
                                    + membershipId(WAREHOUSE_EMAIL))
                            .header("Authorization", "Bearer " + ownerToken)
                            .header("If-Match", "\"" + grant.path("version").asLong() + "\""))
                    .andExpect(status().isOk());
            MvcResult revokedGrantsResult = mockMvc.perform(
                            get("/api/v1/warehouses/" + backingWarehouseId + "/access-grants")
                                    .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk()).andReturn();
            var revokedGrant = StreamSupport.stream(json(revokedGrantsResult).spliterator(), false)
                    .filter(value -> membershipId(WAREHOUSE_EMAIL).equals(value.path("membershipId").asText()))
                    .findFirst().orElseThrow();
            assertThat(revokedGrant.path("status").asText()).isEqualTo("REVOKED");
            assertThat(revokedGrant.path("version").asLong()).isGreaterThan(grant.path("version").asLong());
        }

        UUID dispatchId = UUID.fromString(dispatch.id());
        Long versionAfterAuthorizedNote = tenantJdbc().queryForObject(
                "select version from logistics.dispatch_order where id=?", Long.class, dispatchId);
        Integer eventsAfterAuthorizedNote = tenantJdbc().queryForObject(
                "select count(*) from logistics.dispatch_event where dispatch_order_id=?", Integer.class, dispatchId);
        String revokedWarehouseToken = accessToken(WAREHOUSE_EMAIL, "PLATFORM");

        mockMvc.perform(post(handoffPath)
                        .header("Authorization", "Bearer " + revokedWarehouseToken)
                        .header("If-Match", originalEtag)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(handoffPath)
                        .header("Authorization", "Bearer " + revokedWarehouseToken)
                        .header("If-Match", originalEtag)
                        .header("Idempotency-Key", key + "-new")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        MvcResult remainingWarehouseDispatches = mockMvc.perform(get("/api/v1/dispatch-orders")
                        .header("Authorization", "Bearer " + revokedWarehouseToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(StreamSupport.stream(json(remainingWarehouseDispatches).get("items").spliterator(), false)
                .map(item -> item.path("id").asText()).toList()).doesNotContain(dispatch.id());
        mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                        .header("Authorization", "Bearer " + revokedWarehouseToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id() + "/events")
                        .header("Authorization", "Bearer " + revokedWarehouseToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id() + "/handoff-notes")
                        .header("Authorization", "Bearer " + revokedWarehouseToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id() + "/deliveries")
                        .header("Authorization", "Bearer " + revokedWarehouseToken))
                .andExpect(status().isForbidden());

        String buyerToken = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/dispatch-orders/" + dispatch.id())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());

        assertThat(tenantJdbc().queryForObject("select version from logistics.dispatch_order where id=?",
                Long.class, dispatchId)).isEqualTo(versionAfterAuthorizedNote);
        assertThat(tenantJdbc().queryForObject("select count(*) from logistics.dispatch_event where dispatch_order_id=?",
                Integer.class, dispatchId)).isEqualTo(eventsAfterAuthorizedNote);
    }

    private DispatchResource prepare(DispatchResource value) throws Exception { return mutate(value, "/preparation-starts", "{}", "prepare-" + value.id()); }
    private DispatchResource assign(DispatchResource value) throws Exception { return mutate(value, "/assignments", "{\"responsibleMembershipId\":\"" + membershipId(LOGISTICS_EMAIL) + "\",\"vehicleReference\":\"TRUCK-1\",\"routeName\":\"Route 1\"}", "assign-" + value.id()); }
    private DispatchResource schedule(DispatchResource value) throws Exception { Instant start = Instant.now().plusSeconds(3600); Instant end = start.plusSeconds(7200); return mutate(value, "/schedules", "{\"deliveryWindowStart\":\"" + start + "\",\"deliveryWindowEnd\":\"" + end + "\",\"eta\":\"" + start.plusSeconds(3600) + "\"}", "schedule-" + value.id()); }
    private DispatchResource scheduleAfterReprogram(DispatchResource value) throws Exception { Instant start = Instant.now().plusSeconds(7200); Instant end = start.plusSeconds(7200); return mutate(value, "/schedules", "{\"deliveryWindowStart\":\"" + start + "\",\"deliveryWindowEnd\":\"" + end + "\",\"eta\":\"" + start.plusSeconds(3600) + "\"}", "schedule-after-reprogram-" + value.id()); }
    private DispatchResource ready(DispatchResource value) throws Exception { return mutate(value, "/route-readiness", "{}", "ready-" + value.id()); }
    private DispatchResource reprogram(DispatchResource value) throws Exception { Instant start = Instant.now().plusSeconds(7200); Instant end = start.plusSeconds(7200); return mutate(value, "/reprogrammings", "{\"deliveryWindowStart\":\"" + start + "\",\"deliveryWindowEnd\":\"" + end + "\",\"eta\":\"" + start.plusSeconds(3600) + "\",\"reason\":\"Route changed\"}", "reprogram-" + value.id()); }
    private MvcResult routeStart(DispatchResource value, String key) throws Exception { return mockMvc.perform(post("/api/v1/dispatch-orders/" + value.id() + "/route-starts").header("Authorization", "Bearer " + value.logisticsToken()).header("If-Match", value.etag()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(); }
    private DispatchResource mutate(DispatchResource value, String suffix, String body, String key) throws Exception { MvcResult result = mockMvc.perform(post("/api/v1/dispatch-orders/" + value.id() + suffix).header("Authorization", "Bearer " + value.logisticsToken()).header("If-Match", value.etag()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn(); return new DispatchResource(value.id(), value.dispatchNumber(), result.getResponse().getHeader("ETag"), value.logisticsToken(), value.reservationId(), value.reservationEtag(), value.salesOrderId()); }
    private MvcResult detail(DispatchResource value) throws Exception { return mockMvc.perform(get("/api/v1/dispatch-orders/" + value.id()).header("Authorization", "Bearer " + value.logisticsToken())).andExpect(status().isOk()).andReturn(); }
}
