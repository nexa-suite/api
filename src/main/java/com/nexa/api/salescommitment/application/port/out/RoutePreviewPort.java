package com.nexa.api.salescommitment.application.port.out;

import com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot;

/** Narrow boundary for route previews; provider details stay outside the domain. */
public interface RoutePreviewPort {
    RouteSnapshot preview(MapRoutingPort.MapRouteRequest request);
}
