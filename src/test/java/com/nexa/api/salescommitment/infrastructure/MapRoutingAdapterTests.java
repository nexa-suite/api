package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.port.out.GoogleMapsBoundaryPort;
import com.nexa.api.salescommitment.application.port.out.MapCoordinate;
import com.nexa.api.salescommitment.application.port.out.MapRoutingPort;
import com.nexa.api.salescommitment.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.salescommitment.infrastructure.maps.GoogleMapsRoutingAdapter;
import com.nexa.api.salescommitment.infrastructure.maps.LocalDeterministicMapAdapter;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.math.BigDecimal;

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

    @Test
    void localProviderExposesDeterministicPlaceGeocodeAndDistanceBoundaries() {
        var adapter = new LocalDeterministicMapAdapter();
        var origin = new MapCoordinate(new BigDecimal("-12.0464"), new BigDecimal("-77.0428"));
        var destination = new MapCoordinate(new BigDecimal("-12.0500"), new BigDecimal("-77.0400"));

        assertThat(adapter.search("Av. Real")).singleElement().extracting("placeId").isNotNull();
        assertThat(adapter.geocode("Av. Real 250")).isPresent();
        assertThat(adapter.reverseGeocode(destination)).isPresent();
        assertThat(adapter.estimate(origin, destination)).get().extracting("provider").isEqualTo("LOCAL_DETERMINISTIC");
    }

    @Test
    void localRouteCarriesWarehouseAndDestinationCoordinatesIntoTheSnapshot() {
        var warehouse = new WarehouseSnapshot("warehouse", "WH-LIM-01", "Lima Warehouse", "Av. Warehouse 1",
                "PREFERRED_OPERATIONAL", "OPERATIONAL", 10, true, java.time.Instant.EPOCH,
                new BigDecimal("-12.0400"), new BigDecimal("-77.0300"));
        var address = new DeliveryAddressSnapshot("address", "Main",
                new com.nexa.api.customerbuyerrelationships.contract.Address("STREET", "Av. Lima 123", "Gate 4", "PE",
                        "15", "1501", "150101", null, null, "STREET", "Lima", "123", null, null,
                        null, null, new BigDecimal("-12.0500"), new BigDecimal("-77.0400"), null, "MAP_PIN"), true);

        var route = new LocalDeterministicMapAdapter().preview(new MapRoutingPort.MapRouteRequest(warehouse, address));

        assertThat(route.originLatitude()).isEqualByComparingTo("-12.0400");
        assertThat(route.originLongitude()).isEqualByComparingTo("-77.0300");
        assertThat(route.destinationLatitude()).isEqualByComparingTo("-12.0500");
        assertThat(route.destinationLongitude()).isEqualByComparingTo("-77.0400");
        assertThat(route.mode()).isEqualTo("DRIVING");
    }

    private static MapRoutingPort.MapRouteRequest request() {
        return new MapRoutingPort.MapRouteRequest(
                new WarehouseSnapshot(SalesTestFixtures.WAREHOUSE.toString(), "WH-LIM-01", "Lima Warehouse", "Av. Warehouse 1"),
                new DeliveryAddressSnapshot(SalesTestFixtures.ADDRESS.toString(), "Main", SalesTestFixtures.address(), true));
    }
}
