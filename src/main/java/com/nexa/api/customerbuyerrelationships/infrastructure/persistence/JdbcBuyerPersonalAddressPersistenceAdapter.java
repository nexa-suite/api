package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.domain.model.buyeraddress.BuyerAddress;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-local persistence for membership-owned personal locations. */
public final class JdbcBuyerPersonalAddressPersistenceAdapter implements BuyerAddressPersistencePort {
    private static final String SELECT = "select a.id,a.tenant_id,a.workspace_id,a.buyer_membership_id,a.label,"
            + "coalesce(a.recipient_name,''),coalesce(a.recipient_phone,''),coalesce(a.road_type,'STREET'),"
            + "coalesce(a.street_name,''),coalesce(a.street_number,''),coalesce(a.interior,''),a.address_line,"
            + "coalesce(a.postal_code,''),coalesce(a.reference,''),coalesce(a.receiving_instructions,''),"
            + "coalesce(a.receiving_hours,''),a.latitude,a.longitude,coalesce(a.place_id,''),coalesce(a.source,'MANUAL'),"
            + "coalesce(a.department_code,''),coalesce(a.province_code,''),coalesce(a.district_code,''),"
            + "a.default_address,a.status,a.version,a.address_type from sales.buyer_address a";

    private final JdbcTemplate jdbc;

    public JdbcBuyerPersonalAddressPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
    }

    @Override
    public List<BuyerAddress> list(String tenantId, String workspaceId, String buyerMembershipId) {
        return jdbc.query(SELECT + " where a.tenant_id=? and a.workspace_id=? and a.buyer_membership_id=? "
                        + "and a.status='ACTIVE' order by a.default_address desc,a.updated_at desc,a.id",
                (rs, row) -> address(rs), uuid(tenantId), uuid(workspaceId), uuid(buyerMembershipId));
    }

    @Override
    public Optional<BuyerAddress> find(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId) {
        return jdbc.query(SELECT + " where a.tenant_id=? and a.workspace_id=? and a.buyer_membership_id=? and a.id=?",
                rs -> rs.next() ? Optional.of(address(rs)) : Optional.empty(),
                uuid(tenantId), uuid(workspaceId), uuid(buyerMembershipId), addressId);
    }

    @Override
    public void insert(BuyerAddress value, long nowEpochMillis) {
        Address address = value.address();
        jdbc.update("insert into sales.buyer_address (id,tenant_id,workspace_id,buyer_membership_id,label,recipient_name,"
                        + "recipient_phone,road_type,street_name,street_number,interior,address_line,postal_code,reference,"
                        + "receiving_instructions,receiving_hours,latitude,longitude,place_id,source,department_code,"
                        + "province_code,district_code,address_type) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.tenantId(), value.workspaceId(), value.buyerMembershipId(), value.label(),
                address.recipientName() == null ? value.label() : address.recipientName(), address.recipientPhone(),
                address.roadType(), address.streetName(), address.streetNumber(), address.interior(), address.line(),
                address.postalCode(), address.reference(), address.receivingInstructions(), address.receivingHours(),
                address.latitude(), address.longitude(), address.placeId(), address.source(), address.departmentCode(),
                address.provinceCode(), address.districtCode(), address.addressType());
    }

    @Override
    public int update(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
                      String label, Address address, long expectedVersion) {
        return jdbc.update("update sales.buyer_address set label=?,recipient_name=?,recipient_phone=?,road_type=?,"
                        + "address_type=?,"
                        + "street_name=?,street_number=?,interior=?,address_line=?,postal_code=?,reference=?,"
                        + "receiving_instructions=?,receiving_hours=?,latitude=?,longitude=?,place_id=?,source=?,"
                        + "department_code=?,province_code=?,district_code=?,updated_at=current_timestamp,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and buyer_membership_id=? and id=? "
                        + "and status='ACTIVE' and version=?",
                label, address.recipientName() == null ? label : address.recipientName(), address.recipientPhone(),
                address.roadType(), address.addressType(), address.streetName(), address.streetNumber(),
                address.interior(), address.line(),
                address.postalCode(), address.reference(), address.receivingInstructions(), address.receivingHours(),
                address.latitude(), address.longitude(), address.placeId(), address.source(), address.departmentCode(),
                address.provinceCode(), address.districtCode(), uuid(tenantId), uuid(workspaceId),
                uuid(buyerMembershipId), addressId, expectedVersion);
    }

    @Override
    public int setDefault(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
                          long expectedVersion, long nowEpochMillis) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID membership = uuid(buyerMembershipId);
        boolean activeBuyerLink = Boolean.TRUE.equals(jdbc.query("select workspace_membership_id from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and workspace_membership_id=? and status='ACTIVE' for update",
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), tenant, workspace, membership));
        if (!activeBuyerLink) return 0;
        jdbc.update("update sales.buyer_address set default_address=false,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and buyer_membership_id=? and id<>? "
                        + "and default_address and status='ACTIVE'",
                timestamp(nowEpochMillis), tenant, workspace, membership, addressId);
        return jdbc.update("update sales.buyer_address set default_address=true,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and buyer_membership_id=? and id=? "
                        + "and status='ACTIVE' and version=?",
                timestamp(nowEpochMillis), tenant, workspace, membership, addressId, expectedVersion);
    }

    @Override
    public int deactivate(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
                          long expectedVersion, long nowEpochMillis) {
        return jdbc.update("update sales.buyer_address set status='INACTIVE',default_address=false,updated_at=?,"
                        + "version=version+1 where tenant_id=? and workspace_id=? and buyer_membership_id=? "
                        + "and id=? and status='ACTIVE' and version=?",
                timestamp(nowEpochMillis), uuid(tenantId), uuid(workspaceId), uuid(buyerMembershipId),
                addressId, expectedVersion);
    }

    @Override
    public void installMembershipScope(String buyerMembershipId) {
        String installed = jdbc.queryForObject("select set_config('app.current_membership_id', ?, true)",
                String.class, uuid(buyerMembershipId).toString());
        if (!uuid(buyerMembershipId).toString().equals(installed)) {
            throw new AccessPolicyViolation("Verified Buyer membership scope could not be applied");
        }
    }

    private static BuyerAddress address(java.sql.ResultSet rs) throws java.sql.SQLException {
        Address value = new Address(rs.getString(27), rs.getString(12), rs.getString(14), "PE",
                rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(13),
                rs.getString(15), rs.getString(16), rs.getBigDecimal(17), rs.getBigDecimal(18),
                rs.getString(19), rs.getString(20));
        return BuyerAddress.rehydrate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), value,
                rs.getBoolean(24), "ACTIVE".equalsIgnoreCase(rs.getString(25)), rs.getLong(26));
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(long epochMillis) { return Timestamp.from(Instant.ofEpochMilli(epochMillis)); }
}
