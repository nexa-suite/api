package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Runs one BC-06 request against the currently selected database composition. */
public interface FulfillmentDeliveryRequestRunner {
    <T> T execute(CurrentAccessContext context, Requirements requirements,
                  Function<FulfillmentDeliveryComposition, T> work);

    record Requirements(boolean warehouseGrants, boolean currentDriver, boolean logisticsAssignees,
                        Set<UUID> assignableMemberships, boolean storedDriverLocation) {
        public Requirements {
            assignableMemberships = Set.copyOf(assignableMemberships == null ? Set.of() : assignableMemberships);
        }

        public Requirements(boolean warehouseGrants, boolean currentDriver, boolean logisticsAssignees,
                            Set<UUID> assignableMemberships) {
            this(warehouseGrants, currentDriver, logisticsAssignees, assignableMemberships, false);
        }

        public static Requirements none() {
            return new Requirements(false, false, false, Set.of());
        }

        public static Requirements warehouse() {
            return new Requirements(true, false, false, Set.of());
        }

        public static Requirements warehouseAssignees() {
            return new Requirements(true, false, true, Set.of());
        }

        public static Requirements warehouseAssignable(Collection<UUID> membershipIds) {
            return new Requirements(true, false, false,
                    membershipIds == null ? Set.of() : Set.copyOf(membershipIds));
        }

        public static Requirements driver() {
            return new Requirements(false, true, false, Set.of());
        }

        /** Marks API-as-is workday or coordinate endpoints that are outside the accepted Tenant target. */
        public static Requirements requiringStoredDriverLocation() {
            return new Requirements(false, false, false, Set.of(), true);
        }

        public static Requirements assignable(Collection<UUID> membershipIds) {
            return new Requirements(false, false, false,
                    membershipIds == null ? Set.of() : Set.copyOf(membershipIds));
        }

        public static Requirements assignees() {
            return new Requirements(false, false, true, Set.of());
        }
    }
}
