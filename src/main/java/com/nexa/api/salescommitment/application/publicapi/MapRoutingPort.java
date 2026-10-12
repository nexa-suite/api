package com.nexa.api.salescommitment.application.publicapi;

import com.nexa.api.salescommitment.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.WarehouseSnapshot;

/**
 * Narrow Sales routing contract consumed by runtime composition outside BC-04.
 * Provider details stay outside the public contract.
 */
public interface MapRoutingPort {
    RouteSnapshot preview(MapRouteRequest request);

    record MapRouteRequest(WarehouseSnapshot warehouse, DeliveryAddressSnapshot address) { }
}
