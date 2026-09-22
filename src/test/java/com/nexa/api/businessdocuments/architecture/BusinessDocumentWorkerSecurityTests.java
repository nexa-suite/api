package com.nexa.api.businessdocuments.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDocumentWorkerSecurityTests {
    private static final Path SERVICE = Path.of("src/main/java/com/nexa/api/businessdocuments/infrastructure/persistence/BusinessDocumentService.java");
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V81__harden_document_workers_and_warehouse_logistics_rls.sql");
    private static final Path RESERVATION_WORKER = Path.of("src/main/java/com/nexa/api/inventoryavailability/infrastructure/persistence/WarehouseReservationPersistenceAdapter.java");
    private static final Path WAREHOUSE_SUPPORT = Path.of("src/main/java/com/nexa/api/inventoryavailability/infrastructure/persistence/WarehouseJdbcSupport.java");
    private static final Path EXPIRY_ENTRYPOINT = Path.of("src/main/java/com/nexa/api/inventoryavailability/application/ExpireReservation.java");

    @Test
    void documentWorkerUsesClaimTokensForEveryExternalStorageFinalization() throws Exception {
        String source = Files.readString(SERVICE);

        assertThat(source).contains("upload_claim_token", "upload_lease_until", "claim_token=?", "lease_until > current_timestamp");
        assertThat(source).contains("assertGenerationClaim(request, claimToken)", "completeGeneration(request, claimToken, key, stored)");
        assertThat(source).contains("set status='REQUESTED',failure_code=null,failure_detail=null",
                "recoverStaleGenerationRequests", "lease_until is null or lease_until <= current_timestamp");
        assertThat(source).contains("where tenant_id=? and workspace_id=? and id=? and lifecycle_status='SCANNING'");
        assertThat(source).contains("set lifecycle_status='DELETED',claim_token=null,lease_until=null,upload_claim_token=null,upload_lease_until=null");
        assertThat(source).contains("select tenant_id,id as workspace_id from tenant_management.workspace", "RlsRequestScope.current()");
        assertThat(source).doesNotContain("private EvidenceRow loadEvidenceRow(UUID evidenceId)");
    }

    @Test
    void additiveMigrationFencesQueuesAndForcesOnlyDirectWarehouseLogisticsScopes() throws Exception {
        String migration = Files.readString(MIGRATION);

        assertThat(migration).contains("V81", "ADD COLUMN IF NOT EXISTS lease_until", "ADD COLUMN IF NOT EXISTS claim_token");
        assertThat(migration).contains("warehouse.warehouse", "warehouse.inventory_reservation", "logistics.dispatch_order", "logistics.delivery_incident");
        assertThat(migration).contains("FORCE ROW LEVEL SECURITY", "current_setting(''app.current_tenant_id''", "current_setting(''app.current_workspace_id''");
        assertThat(migration).contains("parallel V79", "V79__add_delivery_attempts_and_continuations.sql", "V80__add_safety_stock_and_inventory_transfers.sql");
    }

    @Test
    void reservationWorkerEstablishesScopeBeforeItsTransactionalWarehouseQuery() throws Exception {
        String adapter = Files.readString(RESERVATION_WORKER);
        String support = Files.readString(WAREHOUSE_SUPPORT);
        String entrypoint = Files.readString(EXPIRY_ENTRYPOINT);

        assertThat(adapter).contains("select tenant_id,id from tenant_management.workspace", "RlsRequestScope.set", "RlsRequestScope.clear");
        assertThat(adapter).contains("where tenant_id=? and workspace_id=? and status='RESERVED'", "transactionTemplate.executeWithoutResult");
        assertThat(adapter).doesNotContain("limit 100 for update skip locked");
        assertThat(support).contains("PROPAGATION_REQUIRES_NEW");
        assertThat(entrypoint).doesNotContain("@Transactional");
    }
}
