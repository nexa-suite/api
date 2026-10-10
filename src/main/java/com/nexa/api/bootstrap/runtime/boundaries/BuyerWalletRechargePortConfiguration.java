package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.payments.application.publicapi.BuyerWalletRechargePort;
import com.nexa.api.payments.application.publicapi.BuyerWalletStoreUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Disabled runtimes expose no recharge implementation; explicit local composition owns enabled flow. */
@Configuration(proxyBeanMethods = false)
public class BuyerWalletRechargePortConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "nexa.tenant-business.buyer-wallet-recharge", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    BuyerWalletRechargePort unavailableBuyerWalletRechargePort() {
        return new BuyerWalletRechargePort() {
            @Override
            public com.nexa.api.payments.application.model.BuyerWalletModels.RechargeIntentView create(
                    com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext context,
                    java.math.BigDecimal amountPEN, String idempotencyKey) {
                throw new BuyerWalletStoreUnavailableException();
            }

            @Override
            public com.nexa.api.payments.application.model.BuyerWalletModels.RechargeView get(
                    com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext context,
                    java.util.UUID rechargeId) {
                throw new BuyerWalletStoreUnavailableException();
            }
        };
    }
}
