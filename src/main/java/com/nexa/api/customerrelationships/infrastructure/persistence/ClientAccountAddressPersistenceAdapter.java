package com.nexa.api.customerrelationships.infrastructure.persistence;

import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.customerrelationships.contract.CustomerAddressReference;
import com.nexa.api.customerrelationships.contract.Address;
import com.nexa.api.customerrelationships.domain.model.clientaccount.ClientAccountAddress;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Scoped address adapter shared by the Client Account aggregate and the Sales ACL. */
@Repository
@Profile("!test")
public class ClientAccountAddressPersistenceAdapter
        implements ClientAccountAddressPersistencePort, CustomerAddressQuery {
    private static final String SELECT = "select a.id,a.tenant_id,a.workspace_id,a.client_account_id,a.label,coalesce(a.recipient_name,''),"
            + "coalesce(a.recipient_phone,''),coalesce(a.road_type,'STREET'),coalesce(a.street_name,''),coalesce(a.street_number,''),"
            + "coalesce(a.interior,''),a.address_line,coalesce(a.postal_code,''),coalesce(a.reference,''),"
            + "coalesce(a.receiving_instructions,''),coalesce(a.receiving_hours,''),a.latitude,a.longitude,coalesce(a.place_id,''),"
            + "coalesce(a.source,'MANUAL'),coalesce(a.department_code,''),coalesce(a.province_code,''),coalesce(a.district_code,''),"
            + "a.default_address,a.status,a.version from sales.client_account_address a";
    private final JdbcTemplate jdbc;

    public ClientAccountAddressPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<ClientAccountAddress> list(String tenantId, String workspaceId, String clientAccountId) {
        return jdbc.query(SELECT + " where a.tenant_id=? and a.workspace_id=? and a.client_account_id=? and a.status='ACTIVE' "
                        + "order by a.default_address desc,a.updated_at desc,a.id",
                (rs, row) -> address(rs), uuid(tenantId), uuid(workspaceId), uuid(clientAccountId));
    }

    @Override
    public Optional<ClientAccountAddress> find(String tenantId, String workspaceId, String clientAccountId, String addressId) {
        return jdbc.query(SELECT + " where a.tenant_id=? and a.workspace_id=? and a.client_account_id=? and a.id=?",
                rs -> rs.next() ? Optional.of(address(rs)) : Optional.empty(),
                uuid(tenantId), uuid(workspaceId), uuid(clientAccountId), uuid(addressId));
    }

    public Optional<ClientAccountAddress> findForBuyer(String tenantId, String workspaceId, String membershipId, String addressId) {
        return jdbc.query(SELECT + " join sales.client_account_membership m on m.client_account_id=a.client_account_id "
                        + "and m.tenant_id=a.tenant_id and m.workspace_id=a.workspace_id "
                        + "where a.tenant_id=? and a.workspace_id=? and m.workspace_membership_id=? and a.id=? and a.status='ACTIVE'",
                rs -> rs.next() ? Optional.of(address(rs)) : Optional.empty(),
                uuid(tenantId), uuid(workspaceId), uuid(membershipId), uuid(addressId));
    }

    public Optional<ClientAccountAddress> findDefaultForBuyer(String tenantId, String workspaceId, String membershipId) {
        return jdbc.query(SELECT + " join sales.client_account_membership m on m.client_account_id=a.client_account_id "
                        + "and m.tenant_id=a.tenant_id and m.workspace_id=a.workspace_id "
                        + "where a.tenant_id=? and a.workspace_id=? and m.workspace_membership_id=? "
                        + "and a.default_address and a.status='ACTIVE'",
                rs -> rs.next() ? Optional.of(address(rs)) : Optional.empty(),
                uuid(tenantId), uuid(workspaceId), uuid(membershipId));
    }

    @Override
    public Optional<CustomerAddressReference> findReference(
            String tenantId, String workspaceId, String customerAccountId, String addressId) {
        return activeReference("a.client_account_id=? and a.id=?", tenantId, workspaceId,
                uuid(customerAccountId), uuid(addressId));
    }

    @Override
    public Optional<CustomerAddressReference> findBuyerReference(
            String tenantId, String workspaceId, String membershipId, String addressId) {
        return activeBuyerReference(tenantId, workspaceId, membershipId, "a.id=?", uuid(addressId));
    }

    @Override
    public Optional<CustomerAddressReference> findDefaultBuyerReference(
            String tenantId, String workspaceId, String membershipId) {
        return activeBuyerReference(tenantId, workspaceId, membershipId, "a.default_address", null);
    }

    @Override
    public void insert(ClientAccountAddress value, long nowEpochMillis) {
                jdbc.update("insert into sales.client_account_address (id,tenant_id,workspace_id,client_account_id,label,recipient_name,"
                        + "recipient_phone,road_type,street_name,street_number,interior,address_line,postal_code,reference,"
                        + "receiving_instructions,receiving_hours,latitude,longitude,place_id,source,department_code,province_code,district_code,"
                        + "default_address,status,version,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.tenantId(), value.workspaceId(), uuid(value.clientAccountId()), value.label(),
                value.address().recipientName() == null ? value.label() : value.address().recipientName(), value.address().recipientPhone(),
                value.address().roadType(), value.address().streetName(), value.address().streetNumber(), value.address().interior(),
                value.address().line(), value.address().postalCode(), value.address().reference(), value.address().receivingInstructions(),
                value.address().receivingHours(), value.address().latitude(), value.address().longitude(), value.address().placeId(),
                value.address().source(), value.address().departmentCode(), value.address().provinceCode(), value.address().districtCode(),
                value.defaultAddress(), value.active() ? "ACTIVE" : "INACTIVE", 0,
                timestamp(nowEpochMillis), timestamp(nowEpochMillis));
    }

    @Override
    public int update(String tenantId, String workspaceId, String clientAccountId, String addressId, String label,
                      String addressType, String line, String reference, String departmentCode, String provinceCode,
                      String districtCode, long expectedVersion) {
        return update(tenantId, workspaceId, clientAccountId, addressId, label,
                new Address(addressType, line, reference, "PE", departmentCode, provinceCode, districtCode), expectedVersion);
    }

    @Override
    public int update(String tenantId, String workspaceId, String clientAccountId, String addressId, String label,
                      Address address, long expectedVersion) {
        return jdbc.update("update sales.client_account_address set label=?,recipient_name=?,recipient_phone=?,road_type=?,street_name=?,"
                        + "street_number=?,interior=?,address_line=?,postal_code=?,reference=?,receiving_instructions=?,receiving_hours=?,"
                        + "latitude=?,longitude=?,place_id=?,source=?,department_code=?,province_code=?,district_code=?,"
                        + "updated_at=current_timestamp,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and id=? and status='ACTIVE' and version=?",
                label, address.recipientName() == null ? label : address.recipientName(), address.recipientPhone(), address.roadType(),
                address.streetName(), address.streetNumber(), address.interior(), address.line(), address.postalCode(), address.reference(),
                address.receivingInstructions(), address.receivingHours(), address.latitude(), address.longitude(), address.placeId(),
                address.source(), address.departmentCode(), address.provinceCode(), address.districtCode(),
                uuid(tenantId), uuid(workspaceId), uuid(clientAccountId), uuid(addressId), expectedVersion);
    }

    @Override
    public int setDefault(String tenantId, String workspaceId, String clientAccountId, String addressId,
                          long expectedVersion, long nowEpochMillis) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), account = uuid(clientAccountId), target = uuid(addressId);
        jdbc.query("select id from sales.client_account_address where tenant_id=? and workspace_id=? and client_account_id=? "
                        + "and status='ACTIVE' for update", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant, workspace, account);
        int changed = jdbc.update("update sales.client_account_address set default_address=false,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and id<>? and default_address and status='ACTIVE'",
                timestamp(nowEpochMillis), tenant, workspace, account, target);
        int targetChanged = jdbc.update("update sales.client_account_address set default_address=true,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and id=? and status='ACTIVE' and version=?",
                timestamp(nowEpochMillis), tenant, workspace, account, target, expectedVersion);
        return targetChanged == 1 ? changed + 1 : 0;
    }

    @Override
    public int deactivate(String tenantId, String workspaceId, String clientAccountId, String addressId,
                          long expectedVersion, long nowEpochMillis) {
        return jdbc.update("update sales.client_account_address set status='INACTIVE',default_address=false,updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and id=? and status='ACTIVE' and version=?",
                timestamp(nowEpochMillis), uuid(tenantId), uuid(workspaceId), uuid(clientAccountId), uuid(addressId), expectedVersion);
    }

    private static ClientAccountAddress address(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ClientAccountAddress.rehydrate((UUID) rs.getObject(1), (UUID) rs.getObject(2), (UUID) rs.getObject(3),
                rs.getObject(4).toString(), rs.getString(5), new Address(rs.getString(8), rs.getString(12), rs.getString(14),
                        "PE", rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(13), rs.getString(15),
                        rs.getString(16), rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getString(19), rs.getString(20)),
                rs.getBoolean(24), "ACTIVE".equalsIgnoreCase(rs.getString(25)), rs.getLong(26));
    }

    private static CustomerAddressReference reference(ClientAccountAddress address) {
        return new CustomerAddressReference(address.id().toString(), address.label(), address.address(), address.defaultAddress());
    }

    private Optional<CustomerAddressReference> activeReference(
            String predicate, String tenantId, String workspaceId, Object... values) {
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(uuid(tenantId)); parameters.add(uuid(workspaceId));
        parameters.addAll(java.util.List.of(values));
        return jdbc.query(SELECT + " join sales.client_account ca on ca.id=a.client_account_id "
                        + "and ca.tenant_id=a.tenant_id and ca.workspace_id=a.workspace_id "
                        + "where a.tenant_id=? and a.workspace_id=? and a.status='ACTIVE' and ca.status='ACTIVE' and " + predicate,
                rs -> rs.next() ? Optional.of(reference(address(rs))) : Optional.empty(), parameters.toArray());
    }

    private Optional<CustomerAddressReference> activeBuyerReference(
            String tenantId, String workspaceId, String membershipId, String predicate, Object value) {
        java.util.List<Object> parameters = new java.util.ArrayList<>(java.util.List.of(
                uuid(tenantId), uuid(workspaceId), uuid(membershipId)));
        if (value != null) parameters.add(value);
        return jdbc.query(SELECT + " join sales.client_account ca on ca.id=a.client_account_id "
                        + "and ca.tenant_id=a.tenant_id and ca.workspace_id=a.workspace_id "
                        + "join sales.client_account_membership m on m.client_account_id=a.client_account_id "
                        + "and m.tenant_id=a.tenant_id and m.workspace_id=a.workspace_id "
                        + "where a.tenant_id=? and a.workspace_id=? and m.workspace_membership_id=? "
                        + "and a.status='ACTIVE' and ca.status='ACTIVE' and " + predicate,
                rs -> rs.next() ? Optional.of(reference(address(rs))) : Optional.empty(), parameters.toArray());
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(long epochMillis) { return Timestamp.from(Instant.ofEpochMilli(epochMillis)); }
}
