package com.nexa.api.warehouse.application;

import com.nexa.api.warehouse.application.port.WarehouseReservationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ExpireReservation {
    private final WarehouseReservationPersistencePort persistence;

    public ExpireReservation(WarehouseReservationPersistencePort persistence) { this.persistence = persistence; }

    @Scheduled(fixedDelay = 60000L)
    @Transactional
    public void execute() { persistence.expireReservations(); }
}
