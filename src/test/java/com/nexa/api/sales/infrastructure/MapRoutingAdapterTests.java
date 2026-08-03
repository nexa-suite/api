package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.SalesTestFixtures;
import com.nexa.api.sales.application.port.out.GoogleMapsBoundaryPort;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.sales.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.sales.infrastructure.maps.GoogleMapsRoutingAdapter;
import com.nexa.api.sales.infrastructure.maps.LocalDeterministicMapAdapter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MapRoutingAdapterTests {
    @Test
    void localRouteIsStableForTheSameWarehouseAndAddress() {
        MapRoutingPort.MapRouteRequest request = request();
        var adapter = new LocalDeterministicMapAdapter();

        assertThat(adapter.preview(request)).isEqualTo(adapter.preview(request));
        assertThat(adapter.preview(request).provider()).isEqualTo("LOCAL_DETERMINISTIC");
    }

    @Test
    void googleAdapterOnlyMapsTheExternalBoundaryResult() {
        var adapter = new GoogleMapsRoutingAdapter(request -> Optional.of(
                new GoogleMapsBoundaryPort.GoogleRoute("google-ref", 2500, 420, "https://maps.google.com/example")));

        var route = adapter.preview(request());

        assertThat(route.provider()).isEqualTo("GOOGLE");
        assertThat(route.reference()).isEqualTo("google-ref");
        assertThat(route.distanceMeters()).isEqualTo(2500);
    }

    private static MapRoutingPort.MapRouteRequest request() {
        return new MapRoutingPort.MapRouteRequest(
                new WarehouseSnapshot(SalesTestFixtures.WAREHOUSE.toString(), "WH-LIM-01", "Lima Warehouse", "Av. Warehouse 1"),
                new DeliveryAddressSnapshot(SalesTestFixtures.ADDRESS.toString(), "Main", SalesTestFixtures.address(), true));
    }
}
