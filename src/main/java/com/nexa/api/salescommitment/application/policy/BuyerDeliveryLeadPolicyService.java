package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
@Profile("!test")
public final class BuyerDeliveryLeadPolicyService implements BuyerDeliveryLeadPolicyQuery {
    private final BuyerDeliveryLeadBusinessPolicy policy;
    private final Clock clock;

    public BuyerDeliveryLeadPolicyService(Clock clock) {
        this.policy = new BuyerDeliveryLeadBusinessPolicy();
        this.clock = Objects.requireNonNull(clock, "Application clock is required");
    }

    @Override
    public BuyerDeliveryLeadPolicyView get(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        context.requireSurface(Surface.PORTAL);
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer membership is required to read this delivery policy");
        }
        context.requirePermission(PermissionKey.BUYER_SALES_READ);
        return new BuyerDeliveryLeadPolicyView(
                BuyerDeliveryLeadBusinessPolicy.MINIMUM_DELIVERY_LEAD_BUSINESS_DAYS,
                policy.minimumAllowedDeliveryDate(clock),
                BuyerDeliveryLeadBusinessPolicy.TIMEZONE);
    }
}
