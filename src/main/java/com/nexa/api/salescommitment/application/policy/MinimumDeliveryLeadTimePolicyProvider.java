package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.util.Objects;

/** Resolves the active Workspace's delivery lead-time policy for a Sales operation. */
@NamedInterface(value = "sales-public", propagate = false)
@FunctionalInterface
public interface MinimumDeliveryLeadTimePolicyProvider {
    MinimumDeliveryLeadTime forWorkspace(CurrentAccessContext context);

    static MinimumDeliveryLeadTimePolicyProvider fixed(MinimumDeliveryLeadTime policy) {
        MinimumDeliveryLeadTime fixedPolicy = Objects.requireNonNull(policy, "Delivery lead-time policy is required");
        return context -> {
            Objects.requireNonNull(context, "Verified Tenant access context is required");
            return fixedPolicy;
        };
    }
}
