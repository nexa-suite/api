package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.port.out.BuyerSalesMessagePersistencePort;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerSalesConversationUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBoundBuyerSalesConversationConfigurationTests {
    @Test
    void cloudProfileRequiresTheExplicitMessagingOptIn() {
        context().withPropertyValues("spring.profiles.active=tenant-cloud")
                .run(application -> assertThat(application)
                        .hasNotFailed().doesNotHaveBean(BuyerSalesConversationUseCase.class));

        context().withPropertyValues("spring.profiles.active=tenant-cloud",
                        "nexa.tenant-business.cloud-buyer-sales-messaging.enabled=true")
                .run(application -> assertThat(application)
                        .hasNotFailed().hasSingleBean(BuyerSalesConversationUseCase.class));
    }

    @Test
    void localPurchaseRequestCapabilityStillEnablesTheExistingBinding() {
        context().withPropertyValues("spring.profiles.active=local",
                        "nexa.tenant-business.purchase-request.enabled=true")
                .run(application -> assertThat(application)
                        .hasNotFailed().hasSingleBean(BuyerSalesConversationUseCase.class));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TenantBoundBuyerSalesConversationConfiguration.class)
                .withBean(TenantBusinessDatabaseRouter.class, () -> new TenantBusinessDatabaseRouter(
                        accessContext -> null, binding -> null, 1))
                .withBean(TenantBuyerSalesMessagePersistenceFactory.class,
                        () -> jdbc -> new NoopBuyerSalesMessagePersistence());
    }

    private static final class NoopBuyerSalesMessagePersistence implements BuyerSalesMessagePersistencePort {
        @Override
        public Optional<UUID> findActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId) {
            throw unsupported();
        }

        @Override
        public Optional<UUID> lockActiveBuyerAccount(UUID tenantId, UUID workspaceId, UUID buyerMembershipId) {
            throw unsupported();
        }

        @Override
        public boolean accountExists(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
            throw unsupported();
        }

        @Override
        public boolean accountAssignedToSalesMembership(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                        UUID salesMembershipId) {
            throw unsupported();
        }

        @Override
        public boolean lockAssignedSalesAccount(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                UUID salesMembershipId, UUID recipientBuyerMembershipId) {
            throw unsupported();
        }

        @Override
        public Optional<Instant> messageCreatedAt(UUID tenantId, UUID workspaceId, UUID clientAccountId,
                                                  UUID buyerMembershipId, UUID messageId) {
            throw unsupported();
        }

        @Override
        public List<com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage> findMessages(
                UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID buyerMembershipId,
                Instant afterCreatedAt, UUID afterMessageId, int limit) {
            throw unsupported();
        }

        @Override
        public boolean insertIfAbsent(UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID messageId,
                                      UUID senderMembershipId,
                                      com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerSalesMessage.SenderKind senderKind,
                                      UUID buyerMembershipId, String idempotencyKey, String requestHash, String body) {
            throw unsupported();
        }

        @Override
        public Optional<StoredMessage> findByIdempotencyKey(UUID tenantId, UUID workspaceId,
                                                            UUID senderMembershipId, String idempotencyKey) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Configuration test persistence must not be called");
        }
    }
}
