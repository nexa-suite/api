package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseTransferPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TransferInventory {
    private final WarehouseTransferPersistencePort persistence;

    public TransferInventory(WarehouseTransferPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.TransferSummary> list(
            CurrentAccessContext context, String sourceWarehouseId, String destinationWarehouseId,
            int page, int size) {
        WarehouseApplicationAuthorization.read(context);
        return persistence.transfers(context, sourceWarehouseId, destinationWarehouseId, page, size);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.TransferSummary get(CurrentAccessContext context, String id) {
        WarehouseApplicationAuthorization.read(context);
        return persistence.transfer(context, id);
    }

    @Transactional
    public WarehouseOperationsService.TransferSummary execute(
            CurrentAccessContext context, WarehouseOperationsService.TransferCommand command,
            long expectedSourceVersion, String idempotencyKey, String correlationId) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.transfer(context, command, expectedSourceVersion, idempotencyKey, correlationId);
    }
}
