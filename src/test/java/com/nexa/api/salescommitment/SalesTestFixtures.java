package com.nexa.api.salescommitment;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.customerbuyerrelationships.contract.CustomerAddressReference;
import com.nexa.api.salescommitment.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.salescommitment.application.port.out.MapRoutingPort;
import com.nexa.api.salescommitment.application.port.out.WarehouseReferencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.salescommitment.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountAddress;
import com.nexa.api.salescommitment.domain.model.commercial.PaymentTerms;
import com.nexa.api.salescommitment.domain.model.credit.CreditProfile;
import com.nexa.api.salescommitment.domain.model.credit.CreditStatus;
import com.nexa.api.salescommitment.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PriceSnapshot;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyLevel;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyOption;
import com.nexa.api.salescommitment.domain.model.reference.PeruGeographyPath;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SalesTestFixtures {
    private SalesTestFixtures() { }

    public static final UUID TENANT = UUID.randomUUID();
    public static final UUID WORKSPACE = UUID.randomUUID();
    public static final UUID MEMBERSHIP = UUID.randomUUID();
    public static final String ACCOUNT = UUID.randomUUID().toString();
    public static final UUID ADDRESS = UUID.randomUUID();
    public static final UUID WAREHOUSE = UUID.randomUUID();

    public static CurrentAccessContext salesContext() {
        return context(MembershipRole.SALES, Surface.PLATFORM);
    }

    public static CurrentAccessContext buyerContext() {
        return context(MembershipRole.BUYER, Surface.PORTAL);
    }

    private static CurrentAccessContext context(MembershipRole role, Surface surface) {
        Membership membership = new Membership(new MembershipId(MEMBERSHIP), UserId.random(), new TenantId(TENANT),
                new WorkspaceId(WORKSPACE), Set.of(role), MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE), surface);
    }

    public static ClientAccountCommercialPort.ClientAccountCommercialProfile commercialProfile() {
        return new ClientAccountCommercialPort.ClientAccountCommercialProfile(ACCOUNT, "Acme Foods",
                "Acme", "20123456789", new CreditProfile(java.math.BigDecimal.valueOf(1000),
                java.math.BigDecimal.valueOf(100), CreditStatus.AVAILABLE),
                new PaymentTerms("CREDIT_30", "Credit 30 days", 30, true), true);
    }

    public static Address address() {
        return Address.peru("STREET", "Av. Lima 123", "Gate 4", "15", "1501", "150101");
    }

    public static ClientAccountAddress savedAddress(boolean defaultAddress, long version) {
        return ClientAccountAddress.rehydrate(ADDRESS, TENANT, WORKSPACE, ACCOUNT, "Main", address(),
                defaultAddress, true, version);
    }

    public static WarehouseReferencePort.WarehouseReference warehouse() {
        return new WarehouseReferencePort.WarehouseReference(WAREHOUSE.toString(), "WH-LIM-01", "Lima Warehouse", "Av. Warehouse 1");
    }

    public static PeruGeographyPath geography() {
        return new PeruGeographyPath(new PeruGeographyOption(15, PeruGeographyLevel.DEPARTMENT, "15", "Lima", null, true),
                new PeruGeographyOption(1501, PeruGeographyLevel.PROVINCE, "1501", "Lima", "15", true),
                new PeruGeographyOption(150101, PeruGeographyLevel.DISTRICT, "150101", "Miraflores", "1501", true));
    }

    public static CatalogItemSnapshot catalogItem() {
        return new CatalogItemSnapshot("ITEM-001", "Frozen berries", "1 kg bag",
                new PriceSnapshot(java.math.BigDecimal.TEN, "PEN"));
    }

    public static SalesSnapshotAssembler assembler() {
        ClientAccountCommercialPort accounts = new ClientAccountCommercialPort() {
            @Override public Optional<ClientAccountCommercialProfile> find(String tenant, String workspace, String account) {
                return ACCOUNT.equals(account) ? Optional.of(commercialProfile()) : Optional.empty();
            }
            @Override public Optional<ClientAccountCommercialProfile> findForBuyer(String tenant, String workspace, String membership) {
                return Optional.of(commercialProfile());
            }
        };
        CustomerAddressQuery addresses = new CustomerAddressQuery() {
            @Override public Optional<CustomerAddressReference> findReference(String tenant, String workspace, String account, String addressId) {
                return ACCOUNT.equals(account) && ADDRESS.toString().equals(addressId) ? Optional.of(addressReference()) : Optional.empty();
            }
            @Override public Optional<CustomerAddressReference> findBuyerReference(String tenant, String workspace, String membership, String addressId) {
                return ADDRESS.toString().equals(addressId) ? Optional.of(addressReference()) : Optional.empty();
            }
            @Override public Optional<CustomerAddressReference> findDefaultBuyerReference(String tenant, String workspace, String membership) {
                return Optional.of(addressReference());
            }
        };
        WarehouseReferencePort warehouses = new WarehouseReferencePort() {
            @Override public Optional<WarehouseReference> findActive(String tenant, String workspace, String warehouseId) {
                return WAREHOUSE.toString().equals(warehouseId) ? Optional.of(warehouse()) : Optional.empty();
            }
            @Override public Optional<WarehouseReference> findPrimary(String tenant, String workspace) { return Optional.of(warehouse()); }
        };
        PeruGeographyPersistencePort geography = new PeruGeographyPersistencePort() {
            @Override public java.util.List<PeruGeographyOption> list(PeruGeographyLevel level, String parentCode) { return java.util.List.of(); }
            @Override public Optional<PeruGeographyPath> resolve(String department, String province, String district) {
                return "15".equals(department) && "1501".equals(province) && "150101".equals(district)
                        ? Optional.of(geography()) : Optional.empty();
            }
        };
        CatalogItemSnapshotLookupPort catalog = new CatalogItemSnapshotLookupPort() {
            @Override public Optional<CatalogItemSnapshot> findActive(String catalogItemId) {
                return "ITEM-001".equals(catalogItemId) ? Optional.of(catalogItem()) : Optional.empty();
            }
        };
        MapRoutingPort maps = request -> new com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot("TEST", "TEST-ROUTE",
                request.warehouse().name(), request.address().label(), 1000, 300, "nexa://test-route");
        return new SalesSnapshotAssembler(accounts, addresses, warehouses, geography, maps, catalog);
    }

    private static CustomerAddressReference addressReference() {
        return new CustomerAddressReference(ADDRESS.toString(), "Main", address(), true);
    }
}
