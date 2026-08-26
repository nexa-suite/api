package com.nexa.api.salescommitment.application.port.out;

import com.nexa.api.salescommitment.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.WarehouseSnapshot;

/** Map boundary used by Sales; implementations can be local or external without leaking SDK types. */
public interface MapRoutingPort {
    RouteSnapshot preview(MapRouteRequest request);

    record MapRouteRequest(WarehouseSnapshot warehouse, DeliveryAddressSnapshot address) { }
}
