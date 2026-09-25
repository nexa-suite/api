package com.nexa.api.salescommitment.infrastructure.purchaserequest;

import com.nexa.api.salescommitment.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.salescommitment.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeTerms;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.MaterialChangePersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcMaterialChangePersistenceAdapter implements MaterialChangePersistencePort {
    private final JdbcTemplate jdbc;
    private final PurchaseRequestPersistencePort requests;
    private final ObjectMapper mapper;

    public JdbcMaterialChangePersistenceAdapter(JdbcTemplate jdbc, PurchaseRequestPersistencePort requests,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MaterialChangeProposalView propose(String tenantId, String workspaceId, String purchaseRequestId,
            long expectedVersion, String actorMembershipId, String reason, MaterialChangeTerms original,
            MaterialChangeTerms proposed, long nowEpochMillis) {
        UUID id = UUID.randomUUID();
        Timestamp now = timestamp(nowEpochMillis);
        if (jdbc.update("update sales.purchase_request set status='CHANGES_PROPOSED',review_note=?,reviewed_by_membership_id=?,reviewed_at=?,updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and status='SUBMITTED' and version=?",
                reason, uuid(actorMembershipId), now, now, uuid(tenantId), uuid(workspaceId), uuid(purchaseRequestId), expectedVersion) != 1) {
            throw new SalesConcurrencyConflictException();
        }
        long proposalVersion = expectedVersion + 1;
        jdbc.update("insert into sales.purchase_request_material_change (id,tenant_id,workspace_id,purchase_request_id,status,proposed_by_membership_id,proposal_reason,original_snapshot,proposed_snapshot,proposed_at,request_version) "
                        + "values (?,?,?,?,'PROPOSED',?,?,?::jsonb,?::jsonb,?,?)",
                id, uuid(tenantId), uuid(workspaceId), uuid(purchaseRequestId), uuid(actorMembershipId), reason,
                json(original), json(proposed), now, proposalVersion);
        return new MaterialChangeProposalView(id.toString(), purchaseRequestId, "PROPOSED", actorMembershipId,
                null, reason, original, proposed, now.toInstant(), null, proposalVersion, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MaterialChangeProposalView> findCurrent(String tenantId, String workspaceId,
            String buyerAccountId, String purchaseRequestId) {
        String buyerFilter = buyerAccountId == null ? "" : " and request.client_account_id=?";
        List<Object> args = new java.util.ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId), uuid(purchaseRequestId)));
        if (buyerAccountId != null) args.add(uuid(buyerAccountId));
        return jdbc.query("select change.id,change.purchase_request_id,change.status,change.proposed_by_membership_id,change.resolved_by_membership_id,change.proposal_reason,"
                        + "change.original_snapshot::text,change.proposed_snapshot::text,change.proposed_at,change.resolved_at,change.request_version,change.resolved_request_version "
                        + "from sales.purchase_request_material_change change join sales.purchase_request request "
                        + "on request.tenant_id=change.tenant_id and request.workspace_id=change.workspace_id and request.id=change.purchase_request_id "
                        + "where change.tenant_id=? and change.workspace_id=? and change.purchase_request_id=? and change.status='PROPOSED'" + buyerFilter,
                rs -> rs.next() ? Optional.of(view(rs)) : Optional.empty(), args.toArray());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseRequestView accept(String tenantId, String workspaceId, String buyerAccountId,
            String purchaseRequestId, String proposalId, long expectedVersion, String actorMembershipId,
            MaterialChangeTerms acceptedTerms, long nowEpochMillis) {
        LockedProposal proposal = lockProposal(tenantId, workspaceId, buyerAccountId, purchaseRequestId,
                proposalId, expectedVersion);
        Timestamp now = timestamp(nowEpochMillis);
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), request = uuid(purchaseRequestId);
        if (jdbc.update("update sales.purchase_request set status='SUBMITTED',priority=?,requested_delivery_date=?,delivery_profile_snapshot=?,payment_option=?,comments=?,review_note=null,reviewed_by_membership_id=?,reviewed_at=?,updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and status='CHANGES_PROPOSED' and version=? and client_account_id=?",
                acceptedTerms.priority(), acceptedTerms.requestedDeliveryDate(), acceptedTerms.deliveryProfileSnapshot(),
                acceptedTerms.paymentOption(), acceptedTerms.comment(), uuid(actorMembershipId), now, now,
                tenant, workspace, request, expectedVersion, uuid(buyerAccountId)) != 1) {
            throw new SalesConcurrencyConflictException();
        }
        jdbc.update("update sales.purchase_request_line set superseded_at=?,updated_at=?,version=version+1 where purchase_request_id=? and superseded_at is null",
                now, now, request);
        for (var line : acceptedTerms.lines()) {
            jdbc.update("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    uuid(line.id()), request, line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(),
                    line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), now, now);
        }
        long resultVersion = expectedVersion + 1;
        if (jdbc.update("update sales.purchase_request_material_change set status='ACCEPTED',resolved_by_membership_id=?,resolved_at=?,resolved_request_version=? "
                        + "where tenant_id=? and workspace_id=? and purchase_request_id=? and id=? and status='PROPOSED' and request_version=?",
                uuid(actorMembershipId), now, resultVersion, tenant, workspace, request, uuid(proposalId), expectedVersion) != 1) {
            throw new SalesConcurrencyConflictException();
        }
        return requests.find(tenantId, workspaceId, buyerAccountId, purchaseRequestId)
                .orElseThrow(() -> new SalesResourceNotFoundException("purchase-request"));
    }

    private LockedProposal lockProposal(String tenantId, String workspaceId, String buyerAccountId,
            String purchaseRequestId, String proposalId, long expectedVersion) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), request = uuid(purchaseRequestId);
        String buyerFilter = buyerAccountId == null ? "" : " and client_account_id=?";
        List<Object> args = new java.util.ArrayList<>(List.of(tenant, workspace, request));
        if (buyerAccountId != null) args.add(uuid(buyerAccountId));
        RequestState state = jdbc.query("select status,version from sales.purchase_request where tenant_id=? and workspace_id=? and id=?" + buyerFilter + " for update",
                rs -> rs.next() ? new RequestState(rs.getString(1), rs.getLong(2)) : null, args.toArray());
        if (state == null) throw new SalesResourceNotFoundException("purchase-request");
        if (!"CHANGES_PROPOSED".equals(state.status()) || state.version() != expectedVersion) {
            throw new SalesConcurrencyConflictException();
        }
        List<Object> proposalArgs = new java.util.ArrayList<>(List.of(tenant, workspace, request, uuid(proposalId), expectedVersion));
        ProposalRow result = jdbc.query("select status from sales.purchase_request_material_change where tenant_id=? and workspace_id=? and purchase_request_id=? and id=? and request_version=? for update",
                rs -> rs.next() ? new ProposalRow(rs.getString(1)) : null, proposalArgs.toArray());
        if (result == null || !"PROPOSED".equals(result.status())) throw new SalesConcurrencyConflictException();
        return new LockedProposal(state.status(), state.version());
    }

    private MaterialChangeProposalView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MaterialChangeProposalView(rs.getObject(1).toString(), rs.getObject(2).toString(), rs.getString(3),
                rs.getObject(4).toString(), rs.getObject(5) == null ? null : rs.getObject(5).toString(), rs.getString(6),
                read(rs.getString(7)), read(rs.getString(8)), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getLong(11),
                rs.getObject(12) == null ? null : rs.getLong(12));
    }

    private String json(MaterialChangeTerms value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Material change snapshot could not be serialized", exception); }
    }

    private MaterialChangeTerms read(String value) {
        try { return mapper.readValue(value, MaterialChangeTerms.class); }
        catch (JacksonException exception) { throw new IllegalStateException("Material change snapshot is invalid", exception); }
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(long epochMillis) { return Timestamp.from(Instant.ofEpochMilli(epochMillis)); }
    private record RequestState(String status, long version) { }
    private record ProposalRow(String status) { }
    private record LockedProposal(String status, long version) { }
}
