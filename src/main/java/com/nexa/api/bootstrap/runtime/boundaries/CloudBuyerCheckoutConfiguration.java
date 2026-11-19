package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Selects the existing Buyer draft, submission and account-address use cases for the cloud router. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-cloud")
@ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-checkout", name = "enabled", havingValue = "true")
public class CloudBuyerCheckoutConfiguration {
    @Bean
    Routes cloudBuyerCheckoutRoutes(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabaseAuthority authority,
            OperationalSettingsAccess settings, TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory policyWriter,
            TenantSalesCommitmentCompositionProvider sales, TenantCustomerRelationshipsCompositionFactory accounts,
            BuyerMembershipDirectory buyers, ActiveSalesMembershipDirectory salesMemberships,
            TenantBusinessTraceabilityBindingsFactory traceability) {
        var policies = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                settings, router, authority, policyWriter);
        var drafts = new TenantBoundPurchaseRequestDraftPort(router, policies, sales);
        var addresses = new TenantBoundCustomerRelationshipsUseCase(router, accounts, buyers,
                salesMemberships, traceability);
        return new Routes(drafts, addresses);
    }

    record Routes(PurchaseRequestDraftPort drafts, ClientAccountAddressUseCase buyerAddresses) {
        ClientAccountAddressUseCase routeAccountAddresses(ClientAccountAddressUseCase existing) {
            return new ClientAccountAddressUseCase() {
                private ClientAccountAddressUseCase forCaller(CurrentAccessContext context) {
                    return context.surface() == Surface.PORTAL && context.hasRole(MembershipRole.BUYER)
                            ? buyerAddresses : existing;
                }

                @Override
                public List<ClientAccountAddressView> list(CurrentAccessContext context, String accountId) {
                    return forCaller(context).list(context, accountId);
                }

                @Override
                public ClientAccountAddressView create(CurrentAccessContext context, String accountId,
                                                       CreateClientAccountAddressCommand command) {
                    return forCaller(context).create(context, accountId, command);
                }

                @Override
                public ClientAccountAddressView update(CurrentAccessContext context, String accountId, String addressId,
                        UpdateClientAccountAddressCommand command, long expectedVersion) {
                    return forCaller(context).update(context, accountId, addressId, command, expectedVersion);
                }

                @Override
                public ClientAccountAddressView setDefault(CurrentAccessContext context, String accountId,
                                                           String addressId, long expectedVersion) {
                    return forCaller(context).setDefault(context, accountId, addressId, expectedVersion);
                }

                @Override
                public ClientAccountAddressView deactivate(CurrentAccessContext context, String accountId,
                                                           String addressId, long expectedVersion) {
                    return forCaller(context).deactivate(context, accountId, addressId, expectedVersion);
                }
            };
        }
    }
}
