package com.nexa.api.sales.infrastructure.buyerrequest;

import com.nexa.api.sales.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.sales.application.buyerrequest.port.BuyerRequestPersistencePort;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persists the Buyer Request Builder result and its immutable snapshot columns. */
@Repository
@Profile("!test")
public class BuyerRequestPersistenceAdapter implements BuyerRequestPersistencePort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BuyerRequestPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public BuyerRequestView save(BuyerRequest request, String tenantId, String workspaceId,
                                 String code, long nowEpochMillis) {
        UUID id = uuid(request.id().value());
        String snapshot = json(request.snapshot());
        jdbc.update("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,"
                        + "requested_delivery_date,delivery_profile_snapshot,payment_option,comments,delivery_address_snapshot,route_snapshot,"
                        + "warehouse_selection_snapshot,commercial_snapshot,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?, ?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,0)",
                id, uuid(tenantId), uuid(workspaceId), uuid(request.clientAccountId()),
                request.buyerMembershipId().value(), code, request.status().name(), "NORMAL",
                request.snapshot().delivery().requestedDate(), snapshot, request.snapshot().payment().option().name(),
                request.snapshot().comments(), json(request.snapshot().address()), json(request.snapshot().route()),
                json(request.snapshot().warehouse()), json(request.snapshot().commercial()), timestamp(nowEpochMillis), timestamp(nowEpochMillis));
        for (var line : request.lines()) {
            jdbc.update("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,"
                            + "quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?, ?,0)",
                    line.id().value(), id, line.catalogItem().catalogItemId(), line.catalogItem().itemName(),
                    line.catalogItem().presentation(), line.quantity().value(), line.unit(), line.catalogItem().price().amount(),
                    line.catalogItem().price().currency(), line.notes(), timestamp(nowEpochMillis), timestamp(nowEpochMillis));
        }
        return find(tenantId, workspaceId, request.id().value()).orElseThrow(
                () -> new SalesResourceNotFoundException("purchase-request"));
    }

    @Override
    public Optional<BuyerRequestView> find(String tenantId, String workspaceId, String requestId) {
        return jdbc.query("select id,code,tenant_id,workspace_id,client_account_id,buyer_membership_id,status,"
                        + "delivery_profile_snapshot,version from sales.purchase_request where tenant_id=? and workspace_id=? and id=?",
                (org.springframework.jdbc.core.ResultSetExtractor<Optional<BuyerRequestView>>) rs -> {
                    if (!rs.next()) return Optional.empty();
                    String id = rs.getObject(1).toString();
                    var snapshot = read(rs.getString(8), com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot.class);
                    List<PurchaseRequestLineView> lines = jdbc.query("select id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,"
                                    + "unit_price_amount,unit_price_currency,notes,version from sales.purchase_request_line where purchase_request_id=? order by created_at,id",
                            (line, row) -> new PurchaseRequestLineView(line.getObject(1).toString(), line.getString(2), line.getString(3),
                                    line.getString(4), line.getBigDecimal(5), line.getString(6), line.getBigDecimal(7),
                                    line.getString(8), line.getString(9), line.getLong(10)), uuid(id));
                    return Optional.of(new BuyerRequestView(id, rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(),
                            rs.getObject(5).toString(), rs.getObject(6).toString(), rs.getString(7), snapshot, lines, rs.getLong(9)));
                }, uuid(tenantId), uuid(workspaceId), uuid(requestId));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to serialize sales snapshot", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to read sales snapshot", exception); }
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(long epochMillis) { return Timestamp.from(Instant.ofEpochMilli(epochMillis)); }
}
