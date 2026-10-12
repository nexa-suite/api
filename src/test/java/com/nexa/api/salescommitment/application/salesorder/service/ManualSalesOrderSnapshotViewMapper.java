package com.nexa.api.salescommitment.application.salesorder.service;

import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.domain.model.salesorder.ManualSalesOrderSnapshot;

/** Builds a complete persistence response in application service tests. */
public final class ManualSalesOrderSnapshotViewMapper {
    private ManualSalesOrderSnapshotViewMapper() { }

    public static ManualSalesOrderView.SnapshotView toView(ManualSalesOrderSnapshot snapshot) {
        var delivery = snapshot.delivery();
        var address = delivery.address();
        var warehouse = delivery.warehouse();
        var route = delivery.route();
        var commercial = snapshot.commercial();
        var credit = commercial.credit();
        var terms = commercial.paymentTerms();
        var payment = snapshot.payment();
        return new ManualSalesOrderView.SnapshotView(
                new ManualSalesOrderView.DeliveryView(delivery.requestedDate(), delivery.notes(),
                        new ManualSalesOrderView.DeliveryAddressView(address.id(), address.label(), address.address(),
                                address.defaultAddress()),
                        new ManualSalesOrderView.WarehouseView(warehouse.id(), warehouse.code(), warehouse.name(),
                                warehouse.address(), warehouse.selectionReason(), warehouse.serviceStatus(),
                                warehouse.priority(), warehouse.preferred(), warehouse.selectedAt(),
                                warehouse.latitude(), warehouse.longitude()),
                        new ManualSalesOrderView.RouteView(route.provider(), route.reference(), route.originLabel(),
                                route.destinationLabel(), route.distanceMeters(), route.durationSeconds(),
                                route.previewUrl(), route.originLatitude(), route.originLongitude(),
                                route.destinationLatitude(), route.destinationLongitude(), route.calculatedAt(),
                                route.mode(), route.path())),
                new ManualSalesOrderView.CommercialView(commercial.clientAccountId(), commercial.businessName(),
                        commercial.commercialName(), commercial.taxIdentifier(),
                        new ManualSalesOrderView.CreditView(credit.limit(), credit.used(), credit.status().name()),
                        new ManualSalesOrderView.PaymentTermsView(terms.code(), terms.label(), terms.dueDays(), terms.credit()),
                        commercial.active()),
                new ManualSalesOrderView.PaymentView(payment.option().name(), payment.termsCode(), payment.amount(),
                        payment.currency(), payment.creditAuthorized()),
                snapshot.capturedAt(), snapshot.notes());
    }
}
