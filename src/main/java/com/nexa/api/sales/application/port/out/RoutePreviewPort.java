package com.nexa.api.sales.application.port.out;

import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;

/** Narrow boundary for route previews; provider details stay outside the domain. */
public interface RoutePreviewPort {
    RouteSnapshot preview(MapRoutingPort.MapRouteRequest request);
}
