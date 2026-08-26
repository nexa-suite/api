package com.nexa.api.businessdocuments.infrastructure.subject;

import com.nexa.api.businessdocuments.application.port.DocumentSubjectLookupPort;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectReference;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectSnapshot;
import com.nexa.api.businessdocuments.domain.model.businessdocument.DocumentSubjectType;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Internal, read-only resolver for the bounded document subjects. It does not own document persistence or storage. */
@Repository
@Profile("!test")
public class JdbcDocumentSubjectLookupAdapter implements DocumentSubjectLookupPort {
    private final JdbcTemplate jdbc;

    public JdbcDocumentSubjectLookupAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public DocumentSubjectSnapshot lookup(String tenantId, String workspaceId, DocumentSubjectReference subject) {
        UUID tenant = uuid(tenantId, "tenantId");
        UUID workspace = uuid(workspaceId, "workspaceId");
        UUID id = uuid(subject.subjectId(), "subjectId");
        return switch (subject.type()) {
            case SALES_ORDER -> resolveSalesOrder(tenant, workspace, id, subject);
            case PURCHASE_REQUEST -> resolvePurchaseRequest(tenant, workspace, id, subject);
            case RECEIVABLE -> resolveReceivable(tenant, workspace, id, subject);
            case PAYMENT -> resolvePayment(tenant, workspace, id, subject);
            case DISPATCH_ORDER -> resolveDispatchOrder(tenant, workspace, id, subject);
            case PROOF_OF_DELIVERY -> resolveProofOfDelivery(tenant, workspace, id, subject);
            case DELIVERY_INCIDENT -> resolveDeliveryIncident(tenant, workspace, id, subject);
        };
    }

    private DocumentSubjectSnapshot resolvePurchaseRequest(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select id,client_account_id,status from sales.purchase_request where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), rs.getObject("client_account_id").toString(), rs.getString("status"), true), tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }
    private DocumentSubjectSnapshot resolveReceivable(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select id,client_account_id,status from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), rs.getObject("client_account_id").toString(), rs.getString("status"), true), tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }
    private DocumentSubjectSnapshot resolvePayment(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select id,client_account_id,status from payments.payment where tenant_id=? and workspace_id=? and id=?", (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), nullable(rs.getObject("client_account_id")), rs.getString("status"), true), tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveSalesOrder(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select id,client_account_id,status from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), rs.getObject("client_account_id").toString(), rs.getString("status"), true),
                tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveDispatchOrder(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select id,client_account_id,status from logistics.dispatch_order where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), nullable(rs.getObject("client_account_id")), rs.getString("status"), true),
                tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveProofOfDelivery(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select p.id,d.client_account_id,p.status from logistics.proof_of_delivery p join logistics.dispatch_order d on d.tenant_id=p.tenant_id and d.workspace_id=p.workspace_id and d.id=p.dispatch_order_id where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), nullable(rs.getObject("client_account_id")), rs.getString("status"), true),
                tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }

    private DocumentSubjectSnapshot resolveDeliveryIncident(UUID tenant, UUID workspace, UUID id, DocumentSubjectReference subject) {
        return jdbc.query("select i.id,d.client_account_id,i.resolution from logistics.delivery_incident i join logistics.dispatch_order d on d.tenant_id=i.tenant_id and d.workspace_id=i.workspace_id and d.id=i.dispatch_order_id where i.tenant_id=? and i.workspace_id=? and i.id=?",
                (rs, row) -> snapshot(tenant, workspace, subject.type(), rs.getObject("id").toString(), nullable(rs.getObject("client_account_id")), rs.getString("resolution") == null ? "OPEN" : "RESOLVED", true),
                tenant, workspace, id).stream().findFirst().orElseGet(() -> absent(tenant, workspace, subject));
    }

    private static DocumentSubjectSnapshot snapshot(UUID tenant, UUID workspace, DocumentSubjectType type, String id, String clientAccountId, String state, boolean exists) {
        return new DocumentSubjectSnapshot(tenant.toString(), workspace.toString(), type, id, clientAccountId, state, exists);
    }

    private static DocumentSubjectSnapshot absent(UUID tenant, UUID workspace, DocumentSubjectReference subject) {
        return snapshot(tenant, workspace, subject.type(), subject.subjectId(), null, "NOT_FOUND", false);
    }

    private static String nullable(Object value) { return value == null ? null : value.toString(); }

    private static UUID uuid(String value, String label) {
        try { return UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(label + " is invalid", e); }
    }
}
