package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerSalesMessagePersistencePort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates BC-02 message persistence bound to one verified Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantBuyerSalesMessagePersistenceFactory
        implements TenantBuyerSalesMessagePersistenceFactory {
    @Override
    public BuyerSalesMessagePersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcTenantBuyerSalesMessagePersistence(Objects.requireNonNull(tenantJdbc,
                "Routed Tenant JDBC session is required"));
    }

    private record JdbcTenantBuyerSalesMessagePersistence(JdbcTemplate jdbc)
            implements BuyerSalesMessagePersistencePort {
        @Override
        public Optional<UUID> findActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId) {
            return jdbc.query("select a.id from sales.client_account a "
                            + "join sales.client_account_membership member on member.client_account_id=a.id "
                            + "and member.tenant_id=a.tenant_id and member.workspace_id=a.workspace_id "
                            + "where a.tenant_id=? and a.workspace_id=? and member.workspace_membership_id=? "
                            + "and member.status='ACTIVE' and a.status='ACTIVE' order by a.id limit 1",
                    (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, buyerMembershipId)
                    .stream().findFirst();
        }

        @Override
        public Optional<UUID> lockActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId) {
            return jdbc.query("select a.id from sales.client_account a "
                            + "join sales.client_account_membership member on member.client_account_id=a.id "
                            + "and member.tenant_id=a.tenant_id and member.workspace_id=a.workspace_id "
                            + "where a.tenant_id=? and a.workspace_id=? and member.workspace_membership_id=? "
                            + "and member.status='ACTIVE' and a.status='ACTIVE' order by a.id limit 1 for update of a, member",
                    (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, buyerMembershipId)
                    .stream().findFirst();
        }

        @Override
        public boolean accountExists(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
            return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account "
                            + "where tenant_id=? and workspace_id=? and id=?)", Boolean.class,
                    tenantId, workspaceId, clientAccountId));
        }

        @Override
        public boolean accountAssignedToSalesMembership(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                        UUID salesMembershipId) {
            return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account "
                            + "where tenant_id=? and workspace_id=? and id=? "
                            + "and sales_owner_membership_id=?)", Boolean.class,
                    tenantId, workspaceId, clientAccountId, salesMembershipId));
        }

        @Override
        public boolean lockAssignedSalesAccount(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                               UUID salesMembershipId, UUID recipientBuyerMembershipId) {
            return !jdbc.query("select a.id from sales.client_account a "
                            + "join sales.client_account_membership member on member.client_account_id=a.id "
                            + "and member.tenant_id=a.tenant_id and member.workspace_id=a.workspace_id "
                            + "where a.tenant_id=? and a.workspace_id=? and a.id=? and a.status='ACTIVE' "
                            + "and a.sales_owner_membership_id=? and member.workspace_membership_id=? and member.status='ACTIVE' "
                            + "for update of a, member",
                    (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, clientAccountId,
                    salesMembershipId, recipientBuyerMembershipId).isEmpty();
        }

        @Override
        public Optional<Instant> messageCreatedAt(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                  UUID buyerMembershipId, UUID messageId) {
            String sql = "select created_at from sales.buyer_sales_message where tenant_id=? "
                    + "and workspace_id=? and client_account_id=? and id=?";
            if (buyerMembershipId != null) sql += " and buyer_membership_id=?";
            return jdbc.query(sql,
                    (rs, row) -> rs.getTimestamp("created_at").toInstant(),
                    buyerMembershipId == null
                            ? new Object[]{tenantId, workspaceId, clientAccountId, messageId}
                            : new Object[]{tenantId, workspaceId, clientAccountId, messageId, buyerMembershipId})
                    .stream().findFirst();
        }

        @Override
        public List<BuyerSalesMessage> findMessages(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                    UUID buyerMembershipId, Instant afterCreatedAt,
                                                    UUID afterMessageId, int limit) {
            String base = "select id, client_account_id, buyer_membership_id, sender_membership_id, sender_kind, body, created_at "
                    + "from sales.buyer_sales_message where tenant_id=? and workspace_id=? and client_account_id=? ";
            if (buyerMembershipId != null) base += "and buyer_membership_id=? ";
            Object[] scope = buyerMembershipId == null
                    ? new Object[]{tenantId, workspaceId, clientAccountId}
                    : new Object[]{tenantId, workspaceId, clientAccountId, buyerMembershipId};
            if (afterCreatedAt == null) {
                return jdbc.query(base + "order by created_at, id limit ?", this::message,
                        append(scope, limit));
            }
            return jdbc.query(base + "and (created_at > ? or (created_at = ? and id > ?)) "
                            + "order by created_at, id limit ?", this::message,
                    append(scope, Timestamp.from(afterCreatedAt), Timestamp.from(afterCreatedAt), afterMessageId, limit));
        }

        @Override
        public boolean insertIfAbsent(UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID messageId,
                                      UUID senderMembershipId, BuyerSalesMessage.SenderKind senderKind,
                                      UUID buyerMembershipId, String idempotencyKey, String requestHash,
                                      String body) {
            return jdbc.update("insert into sales.buyer_sales_message (id,tenant_id,workspace_id,client_account_id,"
                            + "sender_membership_id,sender_kind,buyer_membership_id,idempotency_key,request_hash,body,created_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,clock_timestamp()) on conflict "
                            + "(tenant_id,workspace_id,sender_membership_id,idempotency_key) do nothing",
                    messageId, tenantId, workspaceId, clientAccountId, senderMembershipId,
                    senderKind.name(), buyerMembershipId, idempotencyKey, requestHash, body) == 1;
        }

        @Override
        public Optional<StoredMessage> findByIdempotencyKey(UUID tenantId, UUID workspaceId,
                                                            UUID senderMembershipId, String idempotencyKey) {
            return jdbc.query("select id,client_account_id,sender_membership_id,sender_kind,buyer_membership_id,"
                            + "request_hash,body,created_at from sales.buyer_sales_message where tenant_id=? "
                            + "and workspace_id=? and sender_membership_id=? and idempotency_key=?",
                    (rs, row) -> new StoredMessage(new BuyerSalesMessage(
                                    rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class),
                                    rs.getObject("buyer_membership_id", UUID.class),
                                    rs.getObject("sender_membership_id", UUID.class),
                                    BuyerSalesMessage.SenderKind.valueOf(rs.getString("sender_kind")),
                                    rs.getString("body"), rs.getTimestamp("created_at").toInstant()),
                            rs.getString("request_hash")),
                    tenantId, workspaceId, senderMembershipId, idempotencyKey).stream().findFirst();
        }

        private BuyerSalesMessage message(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
            return new BuyerSalesMessage(rs.getObject("id", UUID.class),
                    rs.getObject("client_account_id", UUID.class),
                    rs.getObject("buyer_membership_id", UUID.class),
                    rs.getObject("sender_membership_id", UUID.class),
                    BuyerSalesMessage.SenderKind.valueOf(rs.getString("sender_kind")),
                    rs.getString("body"), rs.getTimestamp("created_at").toInstant());
        }

        private static Object[] append(Object[] values, Object... trailing) {
            Object[] result = java.util.Arrays.copyOf(values, values.length + trailing.length);
            System.arraycopy(trailing, 0, result, values.length, trailing.length);
            return result;
        }
    }
}
