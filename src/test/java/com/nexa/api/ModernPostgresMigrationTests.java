package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresMigrationTests {
	private static final String MIGRATOR_PASSWORD = "test-only-migrator-password";
	private static final String RUNTIME_PASSWORD = "test-only-runtime-password";
	private static final List<String> PARENT_SCOPED_CHILD_RLS_TABLES = List.of(
			"sales.purchase_request_line",
			"sales.sales_order_line",
			"tenant_management.workspace_settings",
			"tenant_management.operational_settings",
			"tenant_management.notification_preference",
			"tenant_management.organization_invitation_role",
			"tenant_management.role_permission",
			"warehouse.inventory_reservation_line",
			"warehouse.inventory_reservation_allocation",
			"warehouse.reservation_shortage");

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	@Test
	void flywayCreatesOnlyTheModernIdentityAndTenantSchemasWithRequiredTables() throws Exception {
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			statement.execute("create role nexa_runtime login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + RUNTIME_PASSWORD + "'");
			statement.execute("create role nexa_runtime_queue_writer noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
			statement.execute("create role nexa_tenant_database_provisioner login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
			statement.execute("create role nexa_migrator login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + MIGRATOR_PASSWORD + "'");
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).target("168").load().migrate();
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			statement.execute("grant select, update on tenant_management.tenant to nexa_tenant_database_provisioner");
			statement.execute("grant select, update on tenant_management.workspace to nexa_tenant_database_provisioner");
			statement.execute("grant select on tenant_management.workspace_membership to nexa_tenant_database_provisioner");
			prepareV169ScopeHelperPrivileges(statement);
			statement.execute("grant update on tenant_management.tenant_business_database_provisioning_task, tenant_management.tenant_business_database_workspace_anchor_task to nexa_runtime_queue_writer");
			statement.execute("grant nexa_runtime_queue_writer to nexa_runtime");
		}
		assertThatThrownBy(() -> Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("169").load().migrate())
				.as("V169 must fail closed when runtime can SET ROLE to a provisioning-queue writer")
				.hasStackTraceContaining("Tenant database runtime role must be a restricted NOINHERIT login with no role memberships");
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			statement.execute("revoke nexa_runtime_queue_writer from nexa_runtime");
			statement.execute("revoke update on tenant_management.tenant_business_database_provisioning_task, tenant_management.tenant_business_database_workspace_anchor_task from nexa_runtime_queue_writer");
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("169").load().migrate();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("170").load().migrate();
		assertTenantGovernanceScopeMigrationPreflight();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("171").load().migrate();
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			String prepareView = Files.readString(Path.of("ops/database/prepare-v172-buyer-tracking-view.sql"));
			statement.execute(prepareView);
			statement.execute(prepareView);
			String prepareMembershipRls = Files.readString(Path.of("ops/database/prepare-v173-workspace-membership-rls.sql"));
			statement.execute(prepareMembershipRls);
			statement.execute(prepareMembershipRls);
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("173").load().migrate();
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			String prepareMembershipTenantScope = Files.readString(Path.of("ops/database/prepare-v174-workspace-membership-tenant-scope.sql"));
			statement.execute(prepareMembershipTenantScope);
			statement.execute(prepareMembershipTenantScope);
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("174").load().migrate();

		try (var connection = POSTGRES.createConnection("")) {
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select has_function_privilege('nexa_runtime', 'integration.purge_expired_change_events(integer)', 'EXECUTE')")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).as("nexa_runtime must run scheduled change-feed retention").isTrue();
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.role_permission rp join tenant_management.role_definition r on r.id=rp.role_id where r.code='company_owner' and rp.permission_key in ('warehouse.read','warehouse.location.manage','inventory.read','inventory.receive','inventory.adjust','inventory.reserve','inventory.release','inventory.waste','fulfillment.read','fulfillment.manage','logistics.read','dispatch.read','dispatch.assign','dispatch.schedule','dispatch.start_route','dispatch.temperature','dispatch.incident','dispatch.reprogram','dispatch.complete','logistics.analytics.read')")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("Company Owner must not receive operational permissions by default").isZero();
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.role_permission rp join tenant_management.role_definition r on r.id=rp.role_id where r.tenant_id is null and r.workspace_id is null and r.code='tenant_admin' and r.role_type='SYSTEM_RESERVED' and r.status='ACTIVE' and rp.permission_key in ('catalog.read','client.read')")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("Tenant Administrator must receive both accepted business query grants").isEqualTo(2);
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.role_permission rp join tenant_management.role_definition r on r.id=rp.role_id where r.tenant_id is null and r.workspace_id is null and r.code='tenant_admin' and rp.permission_key in ('catalog.product.manage','catalog.taxonomy.manage','catalog.price.manage','catalog.promotion.manage','client.manage','client.address.manage','client.commercial_terms.manage','client.credit.manage','client.credit.configuration.manage')")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("Tenant Administrator query grants must not include catalog or client writes").isZero();
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.role_permission rp join tenant_management.role_definition r on r.id=rp.role_id where r.tenant_id is null and r.workspace_id is null and r.code='logistics' and r.role_type='SYSTEM_TEMPLATE' and r.status='ACTIVE' and rp.permission_key='warehouse.read'")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("Logistics must retain the fixed role's read permission").isEqualTo(1);
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.warehouse_access_grant")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("A read permission must not create explicit Warehouse object grants").isZero();
			}
			try (var statement = connection.createStatement(); var result = statement.executeQuery(
					"select count(*) from tenant_management.role_permission rp join tenant_management.role_definition r on r.id=rp.role_id where r.tenant_id is null and r.workspace_id is null and r.code='sales' and r.status='ACTIVE' and rp.permission_key='client.commercial_terms.manage'")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("Persisted Sales preset retains its existing commercial terms grant").isEqualTo(1);
			}
			assertThat(schemas(connection)).containsExactlyInAnyOrder("iam", "tenant_management", "sales", "integration", "warehouse", "logistics", "catalog_management", "reference_data", "notifications", "audit", "business_documents", "payments");
			assertThat(tables(connection, "public")).containsExactly("flyway_schema_history");
			assertThat(tables(connection, "iam")).containsExactlyInAnyOrder("user_account", "password_credential", "refresh_session", "authentication_failure", "password_reset_request", "security_audit_event", "password_reset_throttle_bucket", "security_notification_outbox", "system_operator_throttle_bucket", "workspace_preview_throttle_bucket", "public_contact_request", "public_contact_throttle_bucket", "access_context_selection_ticket", "internal_support_request", "internal_support_audit_event");
			assertThat(tables(connection, "tenant_management")).containsExactlyInAnyOrder("tenant", "workspace", "workspace_membership", "membership_admin_event", "membership_role_assignment", "organization_registration", "organization_settings", "workspace_settings", "regional_settings", "unit_preferences", "operational_settings", "notification_preference", "tenant_security_settings", "custom_field_definition", "reference_plan_assignment", "organization_invitation", "organization_invitation_role", "organization_invitation_idempotency", "warehouse_access_grant", "workspace_creation_idempotency", "permission_definition", "role_definition", "role_permission", "membership_role_definition", "membership_authorization_state", "organization_registration_draft_idempotency", "tenant_business_database_binding", "wallet_recharge_provider_route", "tenant_business_database_provisioning_task", "tenant_business_database_workspace_anchor_task", "payment_provider_route");
			assertThat(columns(connection, "tenant_management", "tenant_business_database_binding")).containsExactlyInAnyOrder(
				"tenant_id", "database_identity", "credential_secret_reference", "lifecycle_state", "created_at", "updated_at", "version",
				"verified_schema_manifest_sha256", "wallet_recharge_callback_credential_secret_reference", "payment_callback_credential_secret_reference");
			assertThat(tables(connection, "sales")).containsExactlyInAnyOrder("client_account", "client_account_membership", "client_account_address", "purchase_request", "purchase_request_line", "purchase_request_event", "idempotency_record", "idempotency_response", "manual_order_idempotency", "sales_order_sequence", "sales_order", "sales_order_line", "sales_order_event", "purchase_request_draft", "purchase_request_draft_line", "purchase_request_draft_destination", "purchase_request_draft_route", "purchase_request_draft_warehouse_selection", "purchase_request_draft_idempotency", "manual_sales_order_draft", "manual_sales_order_draft_line", "manual_sales_order_draft_idempotency", "commercial_commitment", "commercial_commitment_line", "purchase_request_material_change", "field_visit_evidence");
			assertThat(tables(connection, "integration")).containsExactlyInAnyOrder("change_event", "outbox_event", "inbox_event");
				assertThat(tables(connection, "warehouse")).containsExactlyInAnyOrder("warehouse", "storage_zone", "inventory_lot", "stock_movement", "inventory_event", "inventory_reservation", "inventory_reservation_line", "inventory_reservation_allocation", "reservation_shortage", "command_idempotency", "warehouse_service_configuration", "selection_snapshot", "inventory_temperature_evaluation", "inventory_lot_disposition", "safety_stock_policy", "inventory_transfer", "inventory_transfer_history", "inventory_backing", "inventory_backing_line", "inventory_backing_position", "physical_allocation", "physical_allocation_line", "physical_allocation_event", "physical_allocation_command_idempotency", "inbound_receiving_discrepancy_case", "inventory_cycle_count_correction", "inventory_transfer_receipt_observation", "physical_allocation_substitution_request", "inventory_cycle_count");
                assertThat(tables(connection, "logistics")).containsExactlyInAnyOrder("dispatch_number_counter", "dispatch_order", "dispatch_event", "command_idempotency", "proof_of_delivery", "temperature_reading", "delivery_incident", "buyer_delivery_tracking", "operational_handoff_note", "delivery_attempt", "delivery_attempt_line", "continuation_delivery", "continuation_delivery_line", "fulfillment", "fulfillment_line", "fulfillment_command_idempotency", "fulfillment_event", "picking_result", "picking_result_line", "picking_discrepancy", "picking_discrepancy_resolution", "delivery", "delivery_command_idempotency", "delivery_assignment", "delivery_quantity_outcome", "delivery_event", "temperature_evidence", "temperature_excursion", "proof_of_delivery_addendum", "delivery_handoff_token", "buyer_receipt_fact", "operational_exception_case", "operational_exception_transition", "delivery_load", "delivery_load_stop", "delivery_load_event", "delivery_load_command_idempotency", "customer_instruction_set", "customer_instruction_revision", "driver_workday", "driver_workday_event", "driver_coordinate", "delivery_execution_temperature_evidence", "delivery_execution_hold", "delivery_execution_disposition", "fulfillment_dispatch_window_plan", "operational_exception_coordination_transition", "fulfillment_outgoing_goods_check_line", "delivery_instruction_revision", "driver_delivery_incident", "delivery_instruction_acknowledgement", "fulfillment_driver_assignment", "fulfillment_outgoing_discrepancy_resolution", "fulfillment_handoff_evidence", "fulfillment_outgoing_goods_check", "delivery_active_attempt", "driver_delivery_incident_evidence", "dispatch_handoff_identity", "stock_temperature_exception");
			assertThat(tables(connection, "catalog_management")).containsExactlyInAnyOrder("category", "brand", "product", "product_presentation", "product_asset_reference", "product_visibility", "product_price", "promotion", "promotion_product", "promotion_category", "promotion_client_account", "promotion_rule", "command_idempotency", "seed_import_history", "product_family", "product_variant", "sellable_sku", "sku_price", "promotion_sku", "price_list", "price_list_item", "customer_terms");
			assertThat(columns(connection, "catalog_management", "product_variant")).containsExactlyInAnyOrder(
				"id", "tenant_id", "workspace_id", "family_id", "variant_code", "name", "description", "status",
				"version", "created_at", "updated_at");
			assertThat(columns(connection, "catalog_management", "sellable_sku")).contains("variant_id");
			assertThat(tables(connection, "business_documents")).containsExactlyInAnyOrder("object_storage_object", "business_document", "document_generation_request", "evidence_object");
				assertThat(tables(connection, "payments")).containsExactlyInAnyOrder("credit_account", "receivable", "payment", "payment_attempt", "payment_reconciliation_case", "reconciliation_refund_idempotency", "receivable_allocation", "credit_reservation", "stripe_event_inbox", "payment_event", "financial_adjustment", "financial_ledger_entry", "refund_credit_obligation", "receivable_application", "buyer_wallet_account", "buyer_wallet_reservation", "buyer_wallet_ledger_entry", "buyer_wallet_reservation_event");
				assertThat(columns(connection, "payments", "financial_adjustment"))
					.contains("adjusted_amount", "outstanding_amount", "receivable_version");
			assertThat(tables(connection, "reference_data")).containsExactlyInAnyOrder("department", "province", "district", "road_type");
				assertThat(tables(connection, "notifications")).containsExactlyInAnyOrder("inbox_item", "push_subscription", "push_subscription_command_idempotency", "push_delivery_attempt", "push_delivery_claim");
			assertThat(tables(connection, "audit")).containsExactly("event");
			assertThat(columns(connection, "integration", "outbox_event"))
				.contains("processing_started_at", "lease_until", "claim_token");
			assertThat(columns(connection, "iam", "security_notification_outbox"))
				.contains("processing_started_at", "lease_until", "claim_token", "delivery_key");
			assertThat(columns(connection, "payments", "stripe_event_inbox"))
				.contains("processing_started_at", "lease_until", "claim_token", "wallet_recharge_id");
			assertThat(columns(connection, "payments", "payment_reconciliation_case"))
				.contains("lease_until", "claim_token");
			assertThat(columns(connection, "payments", "reconciliation_refund_idempotency"))
				.containsExactlyInAnyOrder("tenant_id", "workspace_id", "case_id", "actor_membership_id", "idempotency_key", "request_hash", "result_status", "result_json", "failure_kind", "created_at", "completed_at");
			assertThat(columns(connection, "business_documents", "document_generation_request"))
			.contains("processing_started_at", "lease_until", "claim_token");
			assertThat(columns(connection, "business_documents", "evidence_object"))
			.contains("scan_attempt_count", "next_scan_at", "lease_until", "claim_token", "upload_lease_until", "upload_claim_token");
			assertThat(columns(connection, "integration", "change_event")).containsExactlyInAnyOrder(
				"sequence", "event_id", "tenant_id", "workspace_id", "client_account_id", "aggregate_type",
				"aggregate_id", "event_type", "aggregate_version", "public_status", "audiences", "occurred_at", "expires_at");
			assertThat(columns(connection, "iam", "refresh_session")).containsExactlyInAnyOrder(
				"id", "user_id", "membership_id", "surface", "token_hash", "family_id", "created_at", "last_used_at",
				"expires_at", "revoked_at", "family_revoked_at", "replaced_by_session_id", "last_seen_at", "device_label", "coarse_ip", "version");
			assertThat(columns(connection, "iam", "access_context_selection_ticket")).containsExactlyInAnyOrder(
				"ticket_hash", "user_id", "surface", "issued_at", "expires_at", "consumed_at", "revoked_at");
			assertIndexExists(connection, "iam", "ix_access_context_selection_ticket_pending_expiry");
			assertIndexExists(connection, "iam", "ix_access_context_selection_ticket_terminal_retention");
			assertThat(columns(connection, "tenant_management", "workspace_settings")).containsExactlyInAnyOrder(
				"workspace_id", "default_workspace_behavior", "version", "updated_at");
			assertThat(columns(connection, "tenant_management", "operational_settings")).containsExactlyInAnyOrder(
				"workspace_id", "warehouse_preference_strategy", "order_cutoff_policy", "fulfillment_defaults",
				"inventory_visibility_policy", "buyer_availability_policy", "operating_hours_start",
				"operating_hours_end", "order_cutoff_minutes", "thermal_log_required", "purchase_request_expiry_days", "version", "updated_at");
			assertThat(columns(connection, "sales", "purchase_request")).contains("expires_at");
			assertThat(columns(connection, "catalog_management", "promotion")).contains("priority");
			assertThat(indexColumns(connection, "sales", "uq_client_account_one_buyer"))
			.containsExactly("tenant_id", "workspace_id", "client_account_id");
				assertTenantWorkspaceRls(connection);
				assertCurrentSchemaRlsInventory(connection);
				assertWorkspaceMembershipRlsPolicies(connection);
				assertTenantWorkspaceUniqueIndex(connection);
				assertPurchaseRequestExpiryIndex(connection);
				assertIndexExists(connection, "catalog_management", "ix_sellable_sku_gtin_resolution");
				assertIndexExists(connection, "warehouse", "ix_inventory_lot_batch_resolution");
					assertIndexExists(connection, "logistics", "ix_handoff_token_expiry");
					assertIndexExists(connection, "logistics", "uq_picking_result_line_legacy_v17");
					assertIndexExists(connection, "logistics", "uq_picking_result_line_physical_v17");
					assertIndexExists(connection, "notifications", "ix_push_delivery_claim_lease");
					assertTenantDatabaseProvisionerPrivileges(connection);
					assertTenantDatabaseProvisioningScopeIsolation();
					assertCompositeMembershipForeignKeys(connection);
				}
		assertOnlyOneConcurrentOutboxLeaseClaimWins();
		assertParentScopedChildRlsBehavior();
		assertWorkspaceMembershipTenantForeignKey();
		assertWorkspaceMembershipRuntimeTenantScope();
		assertTenantWorkspaceInvariantPreflight();
		assertBuyerDeliveryTrackingUsesInvokerRls();
	}

	private static void assertBuyerDeliveryTrackingUsesInvokerRls() throws Exception {
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID dispatchId = UUID.randomUUID();
		try (var connection = POSTGRES.createConnection("")) {
			try (var statement = connection.createStatement(); var result = statement.executeQuery("""
					select pg_get_userbyid(view.relowner), view.reloptions,
					       has_schema_privilege('nexa_migrator', 'logistics', 'usage'),
					       has_schema_privilege('nexa_migrator', 'logistics', 'create'),
					       has_table_privilege('nexa_migrator', 'logistics.dispatch_order', 'select'),
					       has_table_privilege('nexa_migrator', 'logistics.proof_of_delivery', 'select'),
					       has_table_privilege('nexa_runtime', 'logistics.dispatch_order', 'select'),
					       has_table_privilege('nexa_runtime', 'logistics.proof_of_delivery', 'select'),
					       has_table_privilege('nexa_runtime', 'logistics.buyer_delivery_tracking', 'select')
					  from pg_class view
					 where view.oid = 'logistics.buyer_delivery_tracking'::regclass
					""")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).as("restricted migration role owns tracking view for V172 DDL")
						.isEqualTo("nexa_migrator");
				assertThat(result.getString(2)).as("tracking view delegates RLS and privileges to its invoker")
						.contains("security_invoker=true");
				assertThat(result.getBoolean(3)).as("view owner can resolve Logistics schema").isTrue();
				assertThat(result.getBoolean(4)).as("view owner has no persistent schema CREATE grant").isFalse();
				assertThat(result.getBoolean(5)).as("view owner cannot read dispatch rows").isFalse();
				assertThat(result.getBoolean(6)).as("view owner cannot read proof-of-delivery rows").isFalse();
				assertThat(result.getBoolean(7)).as("runtime reads dispatch rows through forced RLS").isTrue();
				assertThat(result.getBoolean(8)).as("runtime reads POD rows through forced RLS").isTrue();
				assertThat(result.getBoolean(9)).as("runtime retains tracking view contract").isTrue();
			}
		}

		try (var connection = POSTGRES.createConnection("")) {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
			}
			try (var insert = connection.prepareStatement("""
					insert into logistics.dispatch_order
					    (id, tenant_id, workspace_id, dispatch_number, inventory_reservation_id,
					     sales_order_id, client_account_id, status, created_at, updated_at)
					values (?, ?, ?, ?, ?, ?, ?, 'READY_FOR_OPERATIONS', current_timestamp, current_timestamp)
					""")) {
				insert.setObject(1, dispatchId);
				insert.setObject(2, tenantId);
				insert.setObject(3, workspaceId);
				insert.setString(4, "RLS-" + dispatchId.toString().substring(0, 12));
				insert.setObject(5, UUID.randomUUID());
				insert.setObject(6, UUID.randomUUID());
				insert.setObject(7, UUID.randomUUID());
				assertThat(insert.executeUpdate()).isEqualTo(1);
			}
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = origin");
			}
			try (var runtime = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "nexa_runtime", RUNTIME_PASSWORD);
			     var statement = runtime.createStatement()) {
				try (var identity = statement.executeQuery("select current_user, session_user")) {
					assertThat(identity.next()).isTrue();
					assertThat(identity.getString(1)).isEqualTo("nexa_runtime");
					assertThat(identity.getString(2)).isEqualTo("nexa_runtime");
				}
				statement.execute("SELECT set_config('app.current_tenant_id', '', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '', false)");
				assertThat(countById(runtime, dispatchId))
						.as("tracking view fails closed without Tenant/Workspace scope").isZero();
				statement.execute("SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '" + workspaceId + "', false)");
				assertThat(countById(runtime, dispatchId))
						.as("tracking view exposes row inside its Tenant/Workspace scope").isEqualTo(1);
				statement.execute("SELECT set_config('app.current_tenant_id', '" + UUID.randomUUID() + "', false)");
				assertThat(countById(runtime, dispatchId))
						.as("tracking view hides rows from another Tenant").isZero();
			}
		} finally {
			try (var connection = POSTGRES.createConnection(""); var delete = connection.prepareStatement(
					"delete from logistics.dispatch_order where id = ?")) {
				delete.setObject(1, dispatchId);
				delete.executeUpdate();
			}
		}
	}

	private static long countById(java.sql.Connection connection, UUID dispatchId) throws SQLException {
		try (var query = connection.prepareStatement(
				"select count(*) from logistics.buyer_delivery_tracking where dispatch_order_id = ?")) {
			query.setObject(1, dispatchId);
			try (var result = query.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getLong(1);
			}
		}
	}

	private static void assertCurrentSchemaRlsInventory(java.sql.Connection connection) throws Exception {
		var lines = Files.readAllLines(Path.of("docs/security/rls-table-inventory-v174.tsv"));
		assertThat(lines.getFirst()).isEqualTo("table\tcategory\ttenant_id\tworkspace_id\trls_enabled\trls_forced\tpolicy");
		assertThat(lines).isNotEmpty();
		Map<String, List<String>> expected = new LinkedHashMap<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] fields = line.split("\t", -1);
			assertThat(fields).hasSize(7);
			assertThat(fields[1]).isIn("FORCED_RLS_DIRECT_SCOPE", "FORCED_RLS_PARENT_SCOPE", "INHERITED_SCOPE_JUSTIFIED", "GLOBAL_REFERENCE",
					"GLOBAL_IDENTITY_SECURITY", "TECHNICAL_GLOBAL", "CROSS_SCOPE_WORKER", "APPROVED_EXCEPTION");
			assertThat(expected.put(fields[0], List.of(fields[2], fields[3], fields[4], fields[5])))
					.as("each table appears once: %s", fields[0]).isNull();
		}
		Map<String, List<String>> actual = new LinkedHashMap<>();
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select n.nspname || '.' || c.relname, bool_or(a.attname='tenant_id'),
				       bool_or(a.attname='workspace_id'),c.relrowsecurity,c.relforcerowsecurity
				from pg_class c join pg_namespace n on n.oid=c.relnamespace
				left join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
				where c.relkind='r' and n.nspname not in ('pg_catalog','information_schema')
				group by n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity
				order by 1
				""")) {
			while (result.next()) {
				actual.put(result.getString(1), List.of(
						result.getBoolean(2) ? "t" : "f", result.getBoolean(3) ? "t" : "f",
						result.getBoolean(4) ? "t" : "f", result.getBoolean(5) ? "t" : "f"));
			}
		}
		assertThat(actual).as("every current PostgreSQL table must be classified with accurate scope and RLS flags")
				.isEqualTo(expected);
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
				where c.relkind='r' and c.relrowsecurity
				  and not exists (select 1 from pg_policies p where p.schemaname=n.nspname
				      and p.tablename=c.relname and p.qual is not null and p.with_check is not null)
				""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getLong(1)).as("protected tables require USING and WITH CHECK policies").isZero();
		}
	}

	private static void assertWorkspaceMembershipRlsPolicies(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select c.relrowsecurity, c.relforcerowsecurity,
				       pg_get_userbyid(c.relowner),
				       has_table_privilege('nexa_migrator', c.oid, 'SELECT'),
				       has_column_privilege('nexa_migrator', c.oid, 'membership_type', 'SELECT'),
				       has_column_privilege('nexa_migrator', c.oid, 'status', 'SELECT'),
				       pg_get_userbyid(helper.proowner), helper.prosecdef,
				       coalesce(helper.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'],
				       has_function_privilege('nexa_migrator',
				           'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE'),
				       has_function_privilege('nexa_runtime',
				           'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE'),
				       has_function_privilege('nexa_tenant_database_provisioner',
				           'tenant_management.apply_v173_workspace_membership_rls()', 'EXECUTE')
			  from pg_class c
			  cross join pg_proc helper
				 where c.oid = 'tenant_management.workspace_membership'::regclass
			   and helper.oid = 'tenant_management.apply_v173_workspace_membership_rls()'::regprocedure
				""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean(1)).as("membership RLS is enabled").isTrue();
			assertThat(result.getBoolean(2)).as("membership RLS applies to its owner").isTrue();
			assertThat(result.getBoolean(4)).as("migrator has no broad membership SELECT").isFalse();
			assertThat(result.getBoolean(5)).as("migrator cannot read membership type").isFalse();
			assertThat(result.getBoolean(6)).as("migrator cannot read membership status").isFalse();
			assertThat(result.getString(7)).as("fixed DDL helper is owned by the membership table owner")
					.isEqualTo(result.getString(3));
			assertThat(result.getBoolean(8)).as("V173 DDL helper is SECURITY DEFINER for one fixed operation").isTrue();
			assertThat(result.getBoolean(9)).as("V173 DDL helper pins a trusted search_path").isTrue();
			assertThat(result.getBoolean(10)).as("V173 one-shot DDL helper is revoked after migration").isFalse();
			assertThat(result.getBoolean(11)).as("runtime cannot execute the V173 DDL helper").isFalse();
			assertThat(result.getBoolean(12)).as("provisioner cannot execute the V173 DDL helper").isFalse();
		}
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select pg_get_userbyid(membership.relowner), pg_get_userbyid(helper.proowner), helper.prosecdef,
				       coalesce(helper.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'],
				       has_function_privilege('nexa_migrator', helper.oid, 'EXECUTE'),
				       has_function_privilege('nexa_runtime', helper.oid, 'EXECUTE'),
				       has_function_privilege('nexa_tenant_database_provisioner', helper.oid, 'EXECUTE'),
			       has_column_privilege('nexa_migrator', membership.oid, 'tenant_id', 'UPDATE'),
			       has_column_privilege('nexa_migrator', membership.oid, 'tenant_id', 'SELECT'),
			       att.attnotnull
			  from pg_class membership
			  join pg_attribute att on att.attrelid = membership.oid and att.attname = 'tenant_id'
			  cross join pg_proc helper
			 where membership.oid = 'tenant_management.workspace_membership'::regclass
			   and helper.oid = 'tenant_management.apply_v174_workspace_membership_tenant_scope(text)'::regprocedure
			""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(2)).as("V174 DDL helper is owned by the membership table owner").isEqualTo(result.getString(1));
			assertThat(result.getBoolean(3)).as("V174 DDL helper is SECURITY DEFINER for fixed migration DDL").isTrue();
			assertThat(result.getBoolean(4)).as("V174 DDL helper pins trusted search_path").isTrue();
			assertThat(result.getBoolean(5)).as("V174 DDL helper is revoked from migrator after completion").isFalse();
			assertThat(result.getBoolean(6)).as("runtime cannot execute V174 DDL helper").isFalse();
			assertThat(result.getBoolean(7)).as("provisioner cannot execute V174 DDL helper").isFalse();
			assertThat(result.getBoolean(8)).as("temporary migrator tenant_id UPDATE privilege is revoked").isFalse();
			assertThat(result.getBoolean(9)).as("migrator cannot read the new tenant_id column").isFalse();
			assertThat(result.getBoolean(10)).as("membership tenant_id is NOT NULL after V174 backfill").isTrue();
		}
		assertConstraintContains(connection, "tenant_management", "workspace_membership",
				"fk_workspace_membership_tenant_workspace",
				"FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace(tenant_id, id)");
		Map<String, List<String>> policies = new LinkedHashMap<>();
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select policyname, cmd, qual, with_check
				  from pg_policies
				 where schemaname = 'tenant_management'
				   and tablename = 'workspace_membership'
				 order by policyname
				""")) {
			while (result.next()) {
				policies.put(result.getString(1), List.of(
						result.getString(2),
						String.valueOf(result.getString(3)),
						String.valueOf(result.getString(4))));
			}
		}
		assertThat(policies.keySet()).containsExactlyInAnyOrder(
				"v174_workspace_membership_tenant_workspace_scope", "v173_workspace_membership_identity_read");
		List<String> scoped = policies.get("v174_workspace_membership_tenant_workspace_scope");
		assertThat(scoped.get(0)).isEqualTo("ALL");
		assertThat(scoped.get(1)).contains("app.current_tenant_id", "app.current_workspace_id");
		assertThat(scoped.get(2)).contains("app.current_tenant_id", "app.current_workspace_id");
		List<String> identityRead = policies.get("v173_workspace_membership_identity_read");
		assertThat(identityRead.get(0)).isEqualTo("SELECT");
		assertThat(identityRead.get(1)).contains("app.access_context_user_id", "app.workspace_membership_lookup_id",
				"app.workspace_membership_lookup_user_id");
		assertThat(identityRead.get(2)).isEqualTo("null");
	}

	private static void assertOnlyOneConcurrentOutboxLeaseClaimWins() throws Exception {
		UUID tenant = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		UUID event = UUID.randomUUID();
		try (var connection = POSTGRES.createConnection("")) {
			try (var tenantInsert = connection.prepareStatement("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,?,current_timestamp,current_timestamp)")) {
				tenantInsert.setObject(1, tenant); tenantInsert.setString(2, "Lease Test"); tenantInsert.setString(3, "lease-" + tenant); tenantInsert.setString(4, "ACTIVE"); tenantInsert.executeUpdate();
			}
			try (var workspaceInsert = connection.prepareStatement("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)")) {
				workspaceInsert.setObject(1, workspace); workspaceInsert.setObject(2, tenant); workspaceInsert.setString(3, "Lease Test"); workspaceInsert.setString(4, "lease-" + workspace); workspaceInsert.setString(5, "ACTIVE"); workspaceInsert.executeUpdate();
			}
			try (var eventInsert = connection.prepareStatement("insert into integration.outbox_event(event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,schema_version,payload) values (?,?,?,?,?,?,current_timestamp,?,'v1','{}'::jsonb)")) {
				eventInsert.setObject(1, event); eventInsert.setString(2, "LEASE_TEST"); eventInsert.setString(3, "LeaseTest"); eventInsert.setObject(4, UUID.randomUUID()); eventInsert.setObject(5, tenant); eventInsert.setObject(6, workspace); eventInsert.setString(7, "lease-correlation"); eventInsert.executeUpdate();
			}
		}
		try {
			var gate = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var first = executor.submit(() -> claimOutboxLease(gate, event));
				var second = executor.submit(() -> claimOutboxLease(gate, event));
				gate.countDown();
				assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
			}
			assertStaleDeliveryClaimsCannotFinalize(event);
		} finally {
			try (var connection = POSTGRES.createConnection("")) {
				try (var deleteEvent = connection.prepareStatement("delete from integration.outbox_event where event_id=?")) { deleteEvent.setObject(1, event); deleteEvent.executeUpdate(); }
				try (var deleteWorkspace = connection.prepareStatement("delete from tenant_management.workspace where id=?")) { deleteWorkspace.setObject(1, workspace); deleteWorkspace.executeUpdate(); }
				try (var deleteTenant = connection.prepareStatement("delete from tenant_management.tenant where id=?")) { deleteTenant.setObject(1, tenant); deleteTenant.executeUpdate(); }
			}
		}
	}

	private static void assertStaleDeliveryClaimsCannotFinalize(UUID outboxEvent) throws Exception {
		UUID currentToken = UUID.randomUUID();
		UUID staleToken = UUID.randomUUID();
        try (var connection = POSTGRES.createConnection("")) {
			try (var reset = connection.prepareStatement("update integration.outbox_event set status='PROCESSING',processing_started_at=current_timestamp,lease_until=current_timestamp + interval '10 minutes',claim_token=? where event_id=?")) {
				reset.setObject(1, currentToken); reset.setObject(2, outboxEvent); reset.executeUpdate();
			}
			try (var stale = connection.prepareStatement("update integration.outbox_event set status='PUBLISHED',processed_at=current_timestamp,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp")) {
				stale.setObject(1, outboxEvent); stale.setObject(2, staleToken);
				assertThat(stale.executeUpdate()).as("stale outbox worker cannot finalize current claim").isZero();
			}
			try (var current = connection.prepareStatement("update integration.outbox_event set status='PUBLISHED',processed_at=current_timestamp,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp")) {
				current.setObject(1, outboxEvent); current.setObject(2, currentToken);
				assertThat(current.executeUpdate()).as("current outbox worker may finalize claim").isEqualTo(1);
			}

			String inboxEvent = "lease-inbox-" + UUID.randomUUID();
			UUID inboxCurrentToken = UUID.randomUUID();
			try (var insert = connection.prepareStatement("insert into payments.stripe_event_inbox(event_id,event_type,signature_sha256,status,received_at,processing_started_at,lease_until,claim_token) values (?, 'payment_intent.processing', ?, 'PROCESSING', current_timestamp, current_timestamp, current_timestamp + interval '10 minutes', ?)")) {
				insert.setString(1, inboxEvent); insert.setString(2, "0".repeat(64)); insert.setObject(3, inboxCurrentToken); insert.executeUpdate();
			}
			try (var stale = connection.prepareStatement("update payments.stripe_event_inbox set status='PROCESSED',processed_at=current_timestamp,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp")) {
				stale.setString(1, inboxEvent); stale.setObject(2, staleToken);
				assertThat(stale.executeUpdate()).as("stale inbox worker cannot finalize current claim").isZero();
			}
			try (var current = connection.prepareStatement("update payments.stripe_event_inbox set status='PROCESSED',processed_at=current_timestamp,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp")) {
				current.setString(1, inboxEvent); current.setObject(2, inboxCurrentToken);
				assertThat(current.executeUpdate()).as("current inbox worker may finalize claim").isEqualTo(1);
			}
			try (var delete = connection.prepareStatement("delete from payments.stripe_event_inbox where event_id=?")) {
				delete.setString(1, inboxEvent); delete.executeUpdate();
			}
		}
	}

	private static int claimOutboxLease(CountDownLatch gate, UUID event) throws Exception {
		gate.await(10, TimeUnit.SECONDS);
		try (var connection = POSTGRES.createConnection(""); var claim = connection.prepareStatement(
				"update integration.outbox_event set status='PROCESSING',processing_started_at=current_timestamp,lease_until=current_timestamp + interval '10 minutes',claim_token=? where event_id=? and status='PENDING' and next_attempt_at <= current_timestamp")) {
			claim.setObject(1, UUID.randomUUID());
			claim.setObject(2, event);
			return claim.executeUpdate();
		}
	}

	private static Set<String> schemas(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(
				"select schema_name from information_schema.schemata where schema_name not in ('information_schema', 'public') and schema_name not like 'pg_%'")) {
			var values = new java.util.HashSet<String>();
			while (result.next()) values.add(result.getString(1));
			return values;
		}
	}

	private static Set<String> tables(java.sql.Connection connection, String schema) throws SQLException {
		try (var statement = connection.prepareStatement("select table_name from information_schema.tables where table_schema = ?")) {
			statement.setString(1, schema);
			try (var result = statement.executeQuery()) {
				var values = new java.util.HashSet<String>();
				while (result.next()) values.add(result.getString(1));
				return values;
			}
		}
	}

	private static Set<String> columns(java.sql.Connection connection, String schema, String table) throws SQLException {
		try (var statement = connection.prepareStatement("select column_name from information_schema.columns where table_schema = ? and table_name = ?")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (var result = statement.executeQuery()) {
				var values = new java.util.HashSet<String>();
				while (result.next()) values.add(result.getString(1));
				return values;
			}
		}
	}


	private static void assertTenantWorkspaceRls(java.sql.Connection connection) throws Exception {
		Set<String> expectedTables = new java.util.HashSet<>();
		Set<String> expectedTenantWorkspaceTables = new java.util.HashSet<>();
		var inventory = Files.readAllLines(Path.of("docs/security/rls-table-inventory-v174.tsv"));
		for (String line : inventory.subList(1, inventory.size())) {
			String[] fields = line.split("\\t", -1);
			assertThat(fields).hasSize(7);
			if (fields[1].equals("FORCED_RLS_DIRECT_SCOPE") || fields[1].equals("FORCED_RLS_PARENT_SCOPE")) {
				assertThat(fields[4]).isEqualTo("t");
				assertThat(fields[5]).isEqualTo("t");
				expectedTables.add(fields[0]);
				if (fields[2].equals("t") && fields[3].equals("t")) expectedTenantWorkspaceTables.add(fields[0]);
			}
		}
		assertThat(expectedTables).as("V170 classifies stock-temperature exceptions as forced RLS")
				.contains("logistics.stock_temperature_exception");
		assertThat(expectedTenantWorkspaceTables)
				.as("V170 classifies stock-temperature exceptions with direct Tenant/Workspace scope")
				.contains("logistics.stock_temperature_exception");
		Set<String> expectedSinglePolicyTenantWorkspaceTables = new java.util.HashSet<>(expectedTenantWorkspaceTables);
		expectedSinglePolicyTenantWorkspaceTables.remove("iam.internal_support_audit_event");
		// Provisioning queues split SELECT and INSERT into separate policies so the worker
		// can read cross-tenant work without broadening tenant-scoped API inserts.
		expectedSinglePolicyTenantWorkspaceTables.remove("tenant_management.tenant_business_database_provisioning_task");
		expectedSinglePolicyTenantWorkspaceTables.remove("tenant_management.tenant_business_database_workspace_anchor_task");
		Set<String> actualTables = new java.util.HashSet<>();
		Set<String> policyTables = new java.util.HashSet<>();
		Set<String> scopedPolicyTables = new java.util.HashSet<>();
		Set<String> supportAuditPolicyNames = new java.util.HashSet<>();
		Set<String> supportAuditTenantWorkspaceInsertPolicies = new java.util.HashSet<>();
		Set<String> supportAuditVerifiedOperatorPolicies = new java.util.HashSet<>();
		try (var statement = connection.createStatement(); var result = statement.executeQuery("select n.nspname || '.' || c.relname, p.policyname, p.qual, p.with_check from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_policies p on p.schemaname=n.nspname and p.tablename=c.relname where c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity order by 1")) {
			while (result.next()) {
				String table = result.getString(1);
				String policyName = result.getString(2);
				String using = result.getString(3);
				String withCheck = result.getString(4);
				actualTables.add(table);
				policyTables.add(table);
				if (expectedSinglePolicyTenantWorkspaceTables.contains(table)) {
					if (using != null && withCheck != null
							&& using.contains("current_setting('app.current_tenant_id'")
							&& using.contains("current_setting('app.current_workspace_id'")
							&& withCheck.contains("current_setting('app.current_tenant_id'")
							&& withCheck.contains("current_setting('app.current_workspace_id'")) {
						scopedPolicyTables.add(table);
					}
				}
				if ("iam.internal_support_audit_event".equals(table)) {
					supportAuditPolicyNames.add(policyName);
					if ("internal_support_audit_owner_insert_scope".equals(policyName)
							&& using == null && withCheck != null
							&& withCheck.contains("current_setting('app.current_tenant_id'")
							&& withCheck.contains("current_setting('app.current_workspace_id'")) {
						supportAuditTenantWorkspaceInsertPolicies.add(policyName);
					}
					if ("internal_support_audit_verified_operator_scope".equals(policyName)
							&& using != null && withCheck != null
							&& using.contains("current_setting('app.current_internal_operator_id'")
							&& withCheck.contains("current_setting('app.current_internal_operator_id'")) {
						supportAuditVerifiedOperatorPolicies.add(policyName);
					}
				}
			}
		}
		assertThat(actualTables).as("forced RLS tables exactly match the V173 inventory")
				.containsExactlyInAnyOrderElementsOf(expectedTables);
		assertThat(policyTables).as("every forced RLS table has an explicit policy")
				.containsExactlyInAnyOrderElementsOf(expectedTables);
		assertThat(scopedPolicyTables).as("every two-key table has USING and WITH CHECK for both Tenant and Workspace")
				.containsExactlyInAnyOrderElementsOf(expectedSinglePolicyTenantWorkspaceTables);
		assertThat(supportAuditPolicyNames).as("support audit keeps only its owner insert and verified operator policies")
				.containsExactlyInAnyOrder("internal_support_audit_owner_insert_scope",
						"internal_support_audit_verified_operator_scope");
		assertThat(supportAuditTenantWorkspaceInsertPolicies)
				.as("support audit owner inserts require both Tenant and Workspace keys")
				.containsExactly("internal_support_audit_owner_insert_scope");
		assertThat(supportAuditVerifiedOperatorPolicies)
				.as("support audit operator access requires a verified internal operator scope")
				.containsExactly("internal_support_audit_verified_operator_scope");
		assertTenantDatabaseProvisioningQueueScopePolicies(connection);
		assertParentScopedChildRlsPolicies(connection);
	}

	private static void assertParentScopedChildRlsPolicies(java.sql.Connection connection) throws SQLException {
		Map<String, Set<String>> expectedPolicyNames = Map.ofEntries(
				Map.entry("sales.purchase_request_line", Set.of("purchase_request_line_parent_scope")),
				Map.entry("sales.sales_order_line", Set.of("sales_order_line_parent_scope")),
				Map.entry("tenant_management.workspace_settings", Set.of("workspace_settings_parent_scope")),
				Map.entry("tenant_management.operational_settings", Set.of("operational_settings_parent_scope")),
				Map.entry("tenant_management.notification_preference", Set.of("notification_preference_parent_scope")),
				Map.entry("tenant_management.organization_invitation_role", Set.of("organization_invitation_role_parent_scope")),
				Map.entry("tenant_management.role_permission", Set.of("role_permission_visible_role_read",
						"role_permission_custom_role_insert", "role_permission_custom_role_update",
						"role_permission_custom_role_delete")),
				Map.entry("warehouse.inventory_reservation_line", Set.of("inventory_reservation_line_parent_scope")),
				Map.entry("warehouse.inventory_reservation_allocation", Set.of("inventory_reservation_allocation_parent_scope")),
				Map.entry("warehouse.reservation_shortage", Set.of("reservation_shortage_parent_scope")));
		Map<String, Map<String, List<String>>> policies = new LinkedHashMap<>();
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select schemaname || '.' || tablename, policyname, cmd, qual, with_check
				  from pg_policies
				 where (schemaname, tablename) in (
				   ('sales', 'purchase_request_line'), ('sales', 'sales_order_line'),
				   ('tenant_management', 'workspace_settings'), ('tenant_management', 'operational_settings'),
				   ('tenant_management', 'notification_preference'),
				   ('tenant_management', 'organization_invitation_role'),
				   ('tenant_management', 'role_permission'),
				   ('warehouse', 'inventory_reservation_line'),
				   ('warehouse', 'inventory_reservation_allocation'), ('warehouse', 'reservation_shortage')
				 )
				""")) {
			while (result.next()) {
				String table = result.getString(1);
				policies.computeIfAbsent(table, ignored -> new LinkedHashMap<>()).put(result.getString(2),
						List.of(result.getString(3), result.getString(4) == null ? "" : result.getString(4),
								result.getString(5) == null ? "" : result.getString(5)));
			}
		}
		assertThat(policies.keySet()).as("V170 adds parent-scoped policies to all ten inherited-scope tables")
				.containsExactlyInAnyOrderElementsOf(expectedPolicyNames.keySet());
		for (var expected : expectedPolicyNames.entrySet()) {
			assertThat(policies.get(expected.getKey()).keySet())
					.as("explicit parent policy set for %s", expected.getKey())
					.containsExactlyInAnyOrderElementsOf(expected.getValue());
		}

		Map<String, String> parentTables = Map.ofEntries(
				Map.entry("sales.purchase_request_line", "sales.purchase_request"),
				Map.entry("sales.sales_order_line", "sales.sales_order"),
				Map.entry("tenant_management.workspace_settings", "tenant_management.workspace"),
				Map.entry("tenant_management.operational_settings", "tenant_management.workspace"),
				Map.entry("tenant_management.notification_preference", "tenant_management.workspace"),
				Map.entry("tenant_management.organization_invitation_role", "tenant_management.organization_invitation"),
				Map.entry("warehouse.inventory_reservation_line", "warehouse.inventory_reservation"),
				Map.entry("warehouse.inventory_reservation_allocation", "warehouse.inventory_reservation"),
				Map.entry("warehouse.reservation_shortage", "warehouse.inventory_reservation"));
		for (var parent : parentTables.entrySet()) {
			var policy = policies.get(parent.getKey()).values().iterator().next();
			assertThat(policy.get(0)).as("parent policy command for %s", parent.getKey()).isEqualTo("ALL");
			assertThat(policy.get(1)).as("USING parent and Tenant/Workspace scope for %s", parent.getKey())
					.contains(parent.getValue(), "current_tenant_id", "current_workspace_id");
			assertThat(policy.get(2)).as("WITH CHECK parent and Tenant/Workspace scope for %s", parent.getKey())
					.contains(parent.getValue(), "current_tenant_id", "current_workspace_id");
		}

		var rolePermissionPolicies = policies.get("tenant_management.role_permission");
		var visibleRoleRead = rolePermissionPolicies.get("role_permission_visible_role_read");
		assertThat(visibleRoleRead.get(0)).as("system role template grants retain read access through role RLS").isEqualTo("SELECT");
		assertThat(visibleRoleRead.get(1)).contains("tenant_management.role_definition");
		var customInsert = rolePermissionPolicies.get("role_permission_custom_role_insert");
		assertThat(customInsert.get(0)).isEqualTo("INSERT");
		assertThat(customInsert.get(2)).contains("tenant_management.role_definition", "current_tenant_id",
				"current_workspace_id", "CUSTOM");
		var customUpdate = rolePermissionPolicies.get("role_permission_custom_role_update");
		assertThat(customUpdate.get(0)).isEqualTo("UPDATE");
		assertThat(customUpdate.get(1)).contains("tenant_management.role_definition", "current_tenant_id",
				"current_workspace_id", "CUSTOM");
		assertThat(customUpdate.get(2)).contains("tenant_management.role_definition", "current_tenant_id",
				"current_workspace_id", "CUSTOM");
		var customDelete = rolePermissionPolicies.get("role_permission_custom_role_delete");
		assertThat(customDelete.get(0)).isEqualTo("DELETE");
		assertThat(customDelete.get(1)).contains("tenant_management.role_definition", "current_tenant_id",
				"current_workspace_id", "CUSTOM");
		assertParentScopedChildTableOwners(connection);
	}

	private static void assertParentScopedChildTableOwners(java.sql.Connection connection) throws SQLException {
		for (String table : PARENT_SCOPED_CHILD_RLS_TABLES) {
			try (var statement = connection.prepareStatement(
					"select pg_get_userbyid(relowner) from pg_class where oid = ?::regclass")) {
				statement.setString(1, table);
				try (var result = statement.executeQuery()) {
					assertThat(result.next()).as("V170 target table %s exists", table).isTrue();
					assertThat(result.getString(1)).as("V170 target table %s is owned by its migrator", table)
							.isEqualTo("nexa_migrator");
				}
			}
		}
	}

	private static void assertParentScopedChildRlsBehavior() throws Exception {
		UUID tenantA = UUID.randomUUID();
		UUID tenantB = UUID.randomUUID();
		UUID tenantC = UUID.randomUUID();
		UUID workspaceA = UUID.randomUUID();
		UUID workspaceB = UUID.randomUUID();
		UUID workspaceC = UUID.randomUUID();
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		UUID membershipA = UUID.randomUUID();
		UUID membershipB = UUID.randomUUID();
		UUID accountA = UUID.randomUUID();
		UUID accountB = UUID.randomUUID();
		UUID requestA = UUID.randomUUID();
		UUID requestB = UUID.randomUUID();
		UUID lineA = UUID.randomUUID();
		UUID lineB = UUID.randomUUID();

		try (var connection = POSTGRES.createConnection("")) {
			insertParentScopedRlsTenant(connection, tenantA);
			insertParentScopedRlsTenant(connection, tenantB);
			insertParentScopedRlsTenant(connection, tenantC);
			insertParentScopedRlsWorkspace(connection, workspaceA, tenantA);
			insertParentScopedRlsWorkspace(connection, workspaceB, tenantB);
			insertParentScopedRlsWorkspace(connection, workspaceC, tenantC);
			insertParentScopedRlsUser(connection, userA);
			insertParentScopedRlsUser(connection, userB);
			insertParentScopedRlsMembership(connection, tenantA, membershipA, workspaceA, userA);
			insertParentScopedRlsMembership(connection, tenantB, membershipB, workspaceB, userB);
			insertParentScopedRlsClientAccount(connection, accountA, tenantA, workspaceA);
			insertParentScopedRlsClientAccount(connection, accountB, tenantB, workspaceB);
			insertParentScopedRlsPurchaseRequest(connection, requestA, tenantA, workspaceA, accountA, membershipA);
			insertParentScopedRlsPurchaseRequest(connection, requestB, tenantB, workspaceB, accountB, membershipB);
			insertParentScopedRlsPurchaseRequestLine(connection, lineA, requestA, "TEST-SKU-A");
			insertParentScopedRlsPurchaseRequestLine(connection, lineB, requestB, "TEST-SKU-B");
			insertParentScopedRlsWorkspaceSettings(connection, workspaceA);
			insertParentScopedRlsWorkspaceSettings(connection, workspaceB);
		}

		try {
			try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
				statement.execute("SET ROLE nexa_runtime");
				clearParentScopedRlsScope(connection);
				setParentScopedRlsScope(connection, tenantA, workspaceA);

				assertParentScopedRlsRowCount(connection,
						"select count(*) from tenant_management.workspace_settings where workspace_id = ?", workspaceA, 1);
				assertParentScopedRlsRowCount(connection,
						"select count(*) from tenant_management.workspace_settings where workspace_id = ?", workspaceB, 0);
				assertParentScopedRlsRowCount(connection,
						"select count(*) from sales.purchase_request_line where id = ?", lineA, 1);
				assertParentScopedRlsRowCount(connection,
						"select count(*) from sales.purchase_request_line where id = ?", lineB, 0);

				try (var update = connection.prepareStatement(
						"update tenant_management.workspace_settings set default_workspace_behavior = 'COMPACT' where workspace_id = ?")) {
					update.setObject(1, workspaceB);
					assertThat(update.executeUpdate()).as("foreign workspace settings stay invisible to UPDATE").isZero();
				}
				try (var update = connection.prepareStatement(
						"update sales.purchase_request_line set notes = 'cross-tenant update' where id = ?")) {
					update.setObject(1, lineB);
					assertThat(update.executeUpdate()).as("foreign purchase request lines stay invisible to UPDATE").isZero();
				}

				assertParentScopedRlsDenied("workspace_settings INSERT with a foreign Workspace", () -> {
					try (var insert = connection.prepareStatement(
							"insert into tenant_management.workspace_settings(workspace_id, updated_at) values (?, current_timestamp)")) {
						insert.setObject(1, workspaceC);
						insert.executeUpdate();
					}
				});
				assertParentScopedRlsDenied("workspace_settings UPDATE reparented to a foreign Workspace", () -> {
					try (var update = connection.prepareStatement(
							"update tenant_management.workspace_settings set workspace_id = ?, updated_at = current_timestamp where workspace_id = ?")) {
						update.setObject(1, workspaceC);
						update.setObject(2, workspaceA);
						update.executeUpdate();
					}
				});
				assertParentScopedRlsDenied("purchase_request_line INSERT with a foreign purchase request", () -> {
					insertParentScopedRlsPurchaseRequestLine(connection, UUID.randomUUID(), requestB,
							"CROSS-TENANT-INSERT");
				});
				assertParentScopedRlsDenied("purchase_request_line UPDATE reparented to a foreign purchase request", () -> {
					try (var update = connection.prepareStatement(
							"update sales.purchase_request_line set purchase_request_id = ? where id = ?")) {
						update.setObject(1, requestB);
						update.setObject(2, lineA);
						update.executeUpdate();
					}
				});

				clearParentScopedRlsScope(connection);
				assertParentScopedRlsRowCount(connection,
						"select count(*) from tenant_management.workspace_settings where workspace_id = ?", workspaceA, 0);
				assertParentScopedRlsRowCount(connection,
						"select count(*) from sales.purchase_request_line where id = ?", lineA, 0);
				assertParentScopedRlsDenied("workspace_settings INSERT without Tenant/Workspace scope", () -> {
					try (var insert = connection.prepareStatement(
							"insert into tenant_management.workspace_settings(workspace_id, updated_at) values (?, current_timestamp)")) {
						insert.setObject(1, workspaceA);
						insert.executeUpdate();
					}
				});
				assertParentScopedRlsDenied("purchase_request_line INSERT without Tenant/Workspace scope", () ->
						insertParentScopedRlsPurchaseRequestLine(connection, UUID.randomUUID(), requestA,
								"NO-SCOPE-INSERT"));
			}
		} finally {
			try (var connection = POSTGRES.createConnection("")) {
				deleteParentScopedRlsFixtures(connection, lineA, lineB, requestA, requestB, workspaceA, workspaceB,
						workspaceC, accountA, accountB, membershipA, membershipB, userA, userB, tenantA, tenantB, tenantC);
			}
		}
	}

	private static void insertParentScopedRlsTenant(java.sql.Connection connection, UUID tenantId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.tenant(id, name, slug, status, created_at, updated_at)
				values (?, ?, ?, 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, tenantId);
			insert.setString(2, "Parent RLS fixture " + tenantId);
			insert.setString(3, "parent-rls-" + tenantId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsWorkspace(java.sql.Connection connection, UUID workspaceId, UUID tenantId)
			throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.workspace(id, tenant_id, name, slug, status, created_at, updated_at)
				values (?, ?, ?, ?, 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, workspaceId);
			insert.setObject(2, tenantId);
			insert.setString(3, "Parent RLS fixture " + workspaceId);
			insert.setString(4, "parent-rls-" + workspaceId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsUser(java.sql.Connection connection, UUID userId) throws SQLException {
		String suffix = userId.toString();
		try (var insert = connection.prepareStatement("""
				insert into iam.user_account(id, email, normalized_email, username, normalized_username,
				    display_name, preferred_language, status, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, 'es', 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, userId);
			insert.setString(2, "parent-rls-" + suffix + "@example.test");
			insert.setString(3, "parent-rls-" + suffix + "@example.test");
			insert.setString(4, "parent-rls-" + suffix);
			insert.setString(5, "parent-rls-" + suffix);
			insert.setString(6, "Parent RLS fixture");
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsMembership(java.sql.Connection connection, UUID tenantId, UUID membershipId,
			UUID workspaceId, UUID userId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.workspace_membership
				    (id, tenant_id, workspace_id, user_id, membership_type, status, created_at, updated_at)
				values (?, ?, ?, ?, 'BUYER', 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, membershipId);
			insert.setObject(2, tenantId);
			insert.setObject(3, workspaceId);
			insert.setObject(4, userId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsMembershipBeforeV174(java.sql.Connection connection, UUID membershipId,
			UUID workspaceId, UUID userId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.workspace_membership
				    (id, workspace_id, user_id, membership_type, status, created_at, updated_at)
				values (?, ?, ?, 'BUYER', 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, membershipId);
			insert.setObject(2, workspaceId);
			insert.setObject(3, userId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsClientAccount(java.sql.Connection connection, UUID accountId, UUID tenantId,
			UUID workspaceId) throws SQLException {
		String suffix = accountId.toString();
		try (var insert = connection.prepareStatement("""
				insert into sales.client_account
				    (id, tenant_id, workspace_id, code, business_name, commercial_name, tax_country_code,
				     tax_identifier_type, tax_identifier_value, segment, contact_person, contact_email, phone,
				     delivery_profile, payment_condition, status, created_at, updated_at)
				values (?, ?, ?, ?, 'Parent RLS fixture', 'Parent RLS fixture', 'PE', 'RUC', ?, 'WHOLESALE',
				        'Fixture contact', ?, '+51000000000', 'STANDARD', 'CASH', 'ACTIVE', current_timestamp, current_timestamp)
				""")) {
			insert.setObject(1, accountId);
			insert.setObject(2, tenantId);
			insert.setObject(3, workspaceId);
			insert.setString(4, "RLS-" + suffix.substring(0, 8));
			insert.setString(5, suffix);
			insert.setString(6, "contact-" + suffix + "@example.test");
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsPurchaseRequest(java.sql.Connection connection, UUID requestId, UUID tenantId,
			UUID workspaceId, UUID accountId, UUID buyerMembershipId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into sales.purchase_request
				    (id, tenant_id, workspace_id, client_account_id, buyer_membership_id, code, status, priority,
				     created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, 'DRAFT', 'NORMAL', current_timestamp, current_timestamp)
			""")) {
			insert.setObject(1, requestId);
			insert.setObject(2, tenantId);
			insert.setObject(3, workspaceId);
			insert.setObject(4, accountId);
			insert.setObject(5, buyerMembershipId);
			insert.setString(6, "RLS-" + requestId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsPurchaseRequestLine(java.sql.Connection connection, UUID lineId,
			UUID requestId, String catalogItemId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into sales.purchase_request_line
				    (id, purchase_request_id, catalog_item_id, item_name_snapshot, presentation_snapshot,
				     quantity, unit, unit_price_amount, unit_price_currency, created_at, updated_at)
			values (?, ?, ?, 'Fixture item', 'Fixture presentation', 1.0000, 'UNIT', 1.0000, 'USD', current_timestamp, current_timestamp)
			""")) {
			insert.setObject(1, lineId);
			insert.setObject(2, requestId);
			insert.setString(3, catalogItemId);
			insert.executeUpdate();
		}
	}

	private static void insertParentScopedRlsWorkspaceSettings(java.sql.Connection connection, UUID workspaceId)
			throws SQLException {
		try (var insert = connection.prepareStatement(
				"insert into tenant_management.workspace_settings(workspace_id, updated_at) values (?, current_timestamp)")) {
			insert.setObject(1, workspaceId);
			insert.executeUpdate();
		}
	}

	private static void setParentScopedRlsScope(java.sql.Connection connection, UUID tenantId, UUID workspaceId)
			throws SQLException {
		try (var statement = connection.prepareStatement("select set_config(?, ?, false)")) {
			statement.setString(1, "app.current_tenant_id");
			statement.setString(2, tenantId.toString());
			statement.execute();
		}
		try (var statement = connection.prepareStatement("select set_config(?, ?, false)")) {
			statement.setString(1, "app.current_workspace_id");
			statement.setString(2, workspaceId.toString());
			statement.execute();
		}
	}

	private static void clearParentScopedRlsScope(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.prepareStatement("select set_config(?, '', false)")) {
			for (String setting : List.of("app.current_tenant_id", "app.current_workspace_id", "app.workspace_lookup_slug",
					"app.bootstrap_tenant_slug", "app.workspace_membership_lookup_id",
					"app.workspace_membership_lookup_user_id", "app.access_context_user_id",
					"app.current_internal_operator_id")) {
				statement.setString(1, setting);
				statement.execute();
			}
		}
	}

	private static void assertParentScopedRlsRowCount(java.sql.Connection connection, String query, UUID id,
			long expected) throws SQLException {
		try (var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("RLS visible row count for %s", query).isEqualTo(expected);
			}
		}
	}

	private static void assertParentScopedRlsDenied(String description, ParentScopedRlsSqlAction action)
			throws SQLException {
		try {
			action.run();
			throw new AssertionError("RLS unexpectedly allowed " + description);
		} catch (SQLException exception) {
			assertThat(exception.getSQLState()).as("RLS rejects %s", description).isEqualTo("42501");
		}
	}

	@FunctionalInterface
	private interface ParentScopedRlsSqlAction {
		void run() throws SQLException;
	}

	private static void deleteParentScopedRlsFixtures(java.sql.Connection connection, UUID lineA, UUID lineB,
			UUID requestA, UUID requestB, UUID workspaceA, UUID workspaceB, UUID workspaceC, UUID accountA, UUID accountB,
			UUID membershipA, UUID membershipB, UUID userA, UUID userB, UUID tenantA, UUID tenantB, UUID tenantC) throws SQLException {
		for (var entry : List.of(
				Map.entry("delete from sales.purchase_request_line where id in (?, ?)", List.of(lineA, lineB)),
				Map.entry("delete from sales.purchase_request where id in (?, ?)", List.of(requestA, requestB)),
				Map.entry("delete from tenant_management.workspace_settings where workspace_id in (?, ?)", List.of(workspaceA, workspaceB)),
				Map.entry("delete from sales.client_account where id in (?, ?)", List.of(accountA, accountB)),
				Map.entry("delete from tenant_management.workspace_membership where id in (?, ?)", List.of(membershipA, membershipB)),
				Map.entry("delete from iam.user_account where id in (?, ?)", List.of(userA, userB)),
				Map.entry("delete from tenant_management.workspace where id in (?, ?, ?)", List.of(workspaceA, workspaceB, workspaceC)),
				Map.entry("delete from tenant_management.tenant where id in (?, ?, ?)", List.of(tenantA, tenantB, tenantC)))) {
			try (var delete = connection.prepareStatement(entry.getKey())) {
				int parameter = 1;
				for (UUID id : entry.getValue()) delete.setObject(parameter++, id);
				delete.executeUpdate();
			}
		}
	}

	private static void assertTenantDatabaseProvisioningQueueScopePolicies(java.sql.Connection connection)
			throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select
				  exists (select 1 from pg_policies where schemaname='tenant_management'
				    and tablename='tenant_business_database_provisioning_task'
				    and policyname='tenant_business_database_provisioning_task_select_scope'
				    and cmd='SELECT' and qual like '%current_tenant_id%' and qual like '%current_workspace_id%'
				    and qual like '%nexa_tenant_database_provisioner%'),
				  exists (select 1 from pg_policies where schemaname='tenant_management'
				    and tablename='tenant_business_database_provisioning_task'
				    and policyname='tenant_business_database_provisioning_task_insert_scope'
				    and cmd='INSERT' and with_check like '%current_tenant_id%' and with_check like '%current_workspace_id%'),
				  exists (select 1 from pg_policies where schemaname='tenant_management'
				    and tablename='tenant_business_database_workspace_anchor_task'
				    and policyname='tenant_business_database_workspace_anchor_task_select_scope'
				    and cmd='SELECT' and qual like '%current_tenant_id%' and qual like '%current_workspace_id%'
				    and qual like '%nexa_tenant_database_provisioner%'),
				  exists (select 1 from pg_policies where schemaname='tenant_management'
				    and tablename='tenant_business_database_workspace_anchor_task'
				    and policyname='tenant_business_database_workspace_anchor_task_insert_scope'
				    and cmd='INSERT' and with_check like '%current_tenant_id%' and with_check like '%current_workspace_id%')
				""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean(1)).as("provisioning SELECT is tenant scoped with an explicit worker exception").isTrue();
			assertThat(result.getBoolean(2)).as("provisioning INSERT stays tenant and workspace scoped").isTrue();
			assertThat(result.getBoolean(3)).as("workspace-anchor SELECT is tenant scoped with an explicit worker exception").isTrue();
			assertThat(result.getBoolean(4)).as("workspace-anchor INSERT stays tenant and workspace scoped").isTrue();
		}
	}

	private static void assertTenantDatabaseProvisionerPrivileges(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_provisioning_task','SELECT'),
				       has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_provisioning_task','INSERT'),
				       has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_provisioning_task','UPDATE'),
				       has_column_privilege('nexa_runtime','tenant_management.tenant_business_database_provisioning_task','status','UPDATE'),
				       has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_workspace_anchor_task','SELECT'),
				       has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_workspace_anchor_task','INSERT'),
				       has_table_privilege('nexa_runtime','tenant_management.tenant_business_database_workspace_anchor_task','UPDATE'),
				       has_column_privilege('nexa_runtime','tenant_management.tenant_business_database_workspace_anchor_task','status','UPDATE'),
				       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_provisioning_task','SELECT'),
				       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_provisioning_task','INSERT'),
				       has_column_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_provisioning_task','status','UPDATE'),
				       has_column_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_provisioning_task','tenant_id','UPDATE'),
				       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_workspace_anchor_task','SELECT'),
				       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_workspace_anchor_task','INSERT'),
				       has_column_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_workspace_anchor_task','status','UPDATE'),
				       has_column_privilege('nexa_tenant_database_provisioner','tenant_management.tenant_business_database_workspace_anchor_task','tenant_id','UPDATE'),
				       exists (select 1 from pg_policies where schemaname='tenant_management'
				          and tablename='tenant_business_database_provisioning_task'
				          and policyname='tenant_business_database_provisioning_task_worker_update'
				          and cmd='UPDATE' and qual like '%nexa_tenant_database_provisioner%'
				          and with_check like '%nexa_tenant_database_provisioner%'),
				       exists (select 1 from pg_policies where schemaname='tenant_management'
				          and tablename='tenant_business_database_workspace_anchor_task'
				          and policyname='tenant_business_database_workspace_anchor_task_worker_update'
				          and cmd='UPDATE' and qual like '%nexa_tenant_database_provisioner%'
				          and with_check like '%nexa_tenant_database_provisioner%'),
			       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant','SELECT'),
		       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.tenant','UPDATE'),
		       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.workspace','SELECT'),
		       has_table_privilege('nexa_tenant_database_provisioner','tenant_management.workspace_membership','SELECT'),
		       has_column_privilege('nexa_tenant_database_provisioner','tenant_management.workspace_membership','user_id','SELECT'),
		       has_function_privilege('nexa_tenant_database_provisioner','tenant_management.provisioner_scope_exists(uuid,uuid)','EXECUTE'),
				       has_function_privilege('nexa_runtime','tenant_management.provisioner_scope_exists(uuid,uuid)','EXECUTE'),
				       has_database_privilege('nexa_tenant_database_provisioner', current_database(), 'CONNECT'),
				       has_schema_privilege('nexa_tenant_database_provisioner', 'tenant_management', 'USAGE'),
				       has_schema_privilege('nexa_tenant_database_provisioner', 'tenant_management', 'CREATE')
				""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean(1)).as("API runtime retains reads from the provisioning queue").isTrue();
			assertThat(result.getBoolean(2)).as("API runtime retains atomic provisioning-task enqueue").isTrue();
			assertThat(result.getBoolean(3)).as("API runtime cannot update provisioning tasks").isFalse();
			assertThat(result.getBoolean(4)).as("API runtime cannot update provisioning task columns").isFalse();
			assertThat(result.getBoolean(5)).as("API runtime retains reads from workspace-anchor queue").isTrue();
			assertThat(result.getBoolean(6)).as("API runtime retains atomic workspace-anchor enqueue").isTrue();
			assertThat(result.getBoolean(7)).as("API runtime cannot update workspace-anchor tasks").isFalse();
			assertThat(result.getBoolean(8)).as("API runtime cannot update workspace-anchor columns").isFalse();
			assertThat(result.getBoolean(9)).as("provisioner can read provisioning tasks").isTrue();
			assertThat(result.getBoolean(10)).as("provisioner cannot enqueue provisioning tasks").isFalse();
			assertThat(result.getBoolean(11)).as("provisioner can update task lifecycle columns").isTrue();
			assertThat(result.getBoolean(12)).as("provisioner cannot change task Tenant ownership").isFalse();
			assertThat(result.getBoolean(13)).as("provisioner can read workspace-anchor tasks").isTrue();
			assertThat(result.getBoolean(14)).as("provisioner cannot enqueue workspace-anchor tasks").isFalse();
			assertThat(result.getBoolean(15)).as("provisioner can update anchor lifecycle columns").isTrue();
			assertThat(result.getBoolean(16)).as("provisioner cannot change anchor Tenant ownership").isFalse();
			assertThat(result.getBoolean(17)).as("provisioning updates are restricted to the dedicated role by RLS").isTrue();
			assertThat(result.getBoolean(18)).as("anchor updates are restricted to the dedicated role by RLS").isTrue();
			assertThat(result.getBoolean(19)).as("preexisting cross-scope SELECT grants are cleared").isFalse();
			assertThat(result.getBoolean(20)).as("preexisting cross-scope UPDATE grants are cleared").isFalse();
			assertThat(result.getBoolean(21)).as("provisioner has no direct workspace read access").isFalse();
			assertThat(result.getBoolean(22)).as("provisioner has no direct workspace-membership read access").isFalse();
			assertThat(result.getBoolean(23)).as("provisioner cannot read workspace-member user IDs").isFalse();
			assertThat(result.getBoolean(24)).as("provisioner can call the exact-scope verification helper").isTrue();
			assertThat(result.getBoolean(25)).as("API runtime cannot call the exact-scope verification helper").isFalse();
			assertThat(result.getBoolean(26)).as("provisioner can connect to the central database").isTrue();
			assertThat(result.getBoolean(27)).as("provisioner can resolve only its granted central schema").isTrue();
			assertThat(result.getBoolean(28)).as("provisioner cannot create schema objects").isFalse();
		}
	}

	private static void assertTenantDatabaseProvisioningScopeIsolation() throws SQLException {
		var tenants = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var workspaces = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var taskIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var anchorIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
			assertProvisionerScopeFunctionOwner();
		try (var connection = POSTGRES.createConnection("")) {
			for (int index = 0; index < tenants.size(); index++) {
				insertProvisioningIsolationTenantAndWorkspace(connection, tenants.get(index), workspaces.get(index));
			}
			for (int index = 0; index < 2; index++) {
				insertProvisioningIsolationTask(connection, "tenant_business_database_provisioning_task",
						taskIds.get(index), tenants.get(index), workspaces.get(index));
				insertProvisioningIsolationTask(connection, "tenant_business_database_workspace_anchor_task",
						anchorIds.get(index), tenants.get(index), workspaces.get(index));
			}
		}

		try {
			try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
				statement.execute("SET ROLE nexa_runtime");
				statement.execute("SET app.cross_scope_workspace_scan = 'true'");
				statement.execute("SELECT set_config('app.current_tenant_id', '', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '', false)");
				statement.execute("SELECT set_config('app.current_internal_operator_id', '', false)");
				assertProvisioningQueueCount(connection, "tenant_business_database_provisioning_task", 0);
				assertProvisioningQueueCount(connection, "tenant_business_database_workspace_anchor_task", 0);
				assertProvisioningInsertDenied(connection, "tenant_business_database_provisioning_task",
						taskIds.get(2), tenants.get(2), workspaces.get(2));
				assertProvisioningInsertDenied(connection, "tenant_business_database_workspace_anchor_task",
						anchorIds.get(2), tenants.get(2), workspaces.get(2));

				statement.execute("SELECT set_config('app.current_tenant_id', '" + tenants.get(2) + "', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '" + workspaces.get(2) + "', false)");
				insertProvisioningIsolationTask(connection, "tenant_business_database_provisioning_task",
						taskIds.get(2), tenants.get(2), workspaces.get(2));
				insertProvisioningIsolationTask(connection, "tenant_business_database_workspace_anchor_task",
						anchorIds.get(2), tenants.get(2), workspaces.get(2));
				assertProvisioningQueueCount(connection, "tenant_business_database_provisioning_task", 1);
				assertProvisioningQueueCount(connection, "tenant_business_database_workspace_anchor_task", 1);
			}

			try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
				statement.execute("SET ROLE nexa_tenant_database_provisioner");
				statement.execute("SET app.cross_scope_workspace_scan = 'true'");
				statement.execute("SELECT set_config('app.current_tenant_id', '', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '', false)");
				statement.execute("SELECT set_config('app.current_internal_operator_id', '', false)");
				assertProvisioningQueueCount(connection, "tenant_business_database_provisioning_task", 3);
				assertProvisioningQueueCount(connection, "tenant_business_database_workspace_anchor_task", 3);
				assertProvisionerDirectScopeReadsDenied(connection);
				assertProvisionerScopeExists(connection, tenants.get(0), workspaces.get(0), false);

				statement.execute("SELECT set_config('app.current_tenant_id', '" + tenants.get(0) + "', false)");
				statement.execute("SELECT set_config('app.current_workspace_id', '" + workspaces.get(0) + "', false)");
				assertProvisionerScopeExists(connection, tenants.get(0), workspaces.get(0), true);
				assertProvisionerScopeExists(connection, tenants.get(1), workspaces.get(1), false);
				assertProvisionerScopeExists(connection, tenants.get(0), workspaces.get(1), false);
			}
		} finally {
			try (var connection = POSTGRES.createConnection("")) {
				deleteProvisioningIsolationRows(connection, "tenant_management.tenant_business_database_workspace_anchor_task", tenants);
				deleteProvisioningIsolationRows(connection, "tenant_management.tenant_business_database_provisioning_task", tenants);
				deleteProvisioningIsolationRows(connection, "tenant_management.workspace", tenants);
				deleteProvisioningIsolationRows(connection, "tenant_management.tenant", tenants);
			}
		}
	}

	private static void assertProvisionerScopeFunctionOwner() throws SQLException {
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("""
					select pg_get_userbyid(p.proowner), r.rolsuper, r.rolbypassrls,
					       has_column_privilege(r.oid, 'tenant_management.workspace', 'tenant_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace', 'id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace', 'slug', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace', 'status', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace_membership', 'id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace_membership', 'workspace_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace_membership', 'user_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.tenant_business_database_binding', 'tenant_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.tenant_business_database_binding', 'lifecycle_state', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.tenant_business_database_workspace_anchor_task', 'tenant_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.tenant_business_database_workspace_anchor_task', 'workspace_id', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.tenant_business_database_workspace_anchor_task', 'status', 'SELECT'),
					       has_column_privilege(r.oid, 'tenant_management.workspace', 'name', 'SELECT')
					  from pg_proc p join pg_roles r on r.rolname='nexa_migrator'
					 where p.oid='tenant_management.provisioner_scope_exists(uuid, uuid)'::regprocedure
					""")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).as("scope helper runs as the restricted migration role").isEqualTo("nexa_migrator");
				assertThat(result.getBoolean(2)).as("migration function owner is not superuser").isFalse();
				assertThat(result.getBoolean(3)).as("migration function owner does not bypass RLS").isFalse();
				assertThat(result.getBoolean(4)).as("migration function owner can evaluate Workspace tenant scope policy").isTrue();
				assertThat(result.getBoolean(5)).as("migration function owner can evaluate Workspace identity scope policy").isTrue();
				assertThat(result.getBoolean(6)).as("migration function owner can evaluate Workspace lookup policy").isTrue();
				assertThat(result.getBoolean(7)).as("migration function owner can evaluate Workspace readiness policy").isTrue();
				assertThat(result.getBoolean(8)).as("migration function owner can evaluate membership row policy id").isTrue();
				assertThat(result.getBoolean(9)).as("migration function owner can evaluate membership row policy workspace").isTrue();
				assertThat(result.getBoolean(10)).as("migration function owner can evaluate membership row policy user").isTrue();
				assertThat(result.getBoolean(11)).as("migration function owner can evaluate binding scope policy").isTrue();
				assertThat(result.getBoolean(12)).as("migration function owner can evaluate binding readiness policy").isTrue();
				assertThat(result.getBoolean(13)).as("migration function owner can evaluate anchor scope policy").isTrue();
				assertThat(result.getBoolean(14)).as("migration function owner can evaluate anchor identity policy").isTrue();
				assertThat(result.getBoolean(15)).as("migration function owner can evaluate anchor readiness policy").isTrue();
				assertThat(result.getBoolean(16)).as("migration function owner has no unrelated Workspace name read grant").isFalse();
			}
		}
	}

	private static void prepareV169ScopeHelperPrivileges(java.sql.Statement statement) throws Exception {
		statement.execute(Files.readString(Path.of("ops/database/prepare-v169-tenant-provisioner.sql")));
	}

	private static void assertProvisionerDirectScopeReadsDenied(java.sql.Connection connection) throws SQLException {
		for (var query : List.of(
				"select id from tenant_management.tenant limit 1",
				"select tenant_id from tenant_management.workspace limit 1",
				"select user_id from tenant_management.workspace_membership limit 1")) {
			try (var statement = connection.createStatement()) {
				statement.executeQuery(query);
				throw new AssertionError("Provisioner unexpectedly has direct identity-scope read access: " + query);
			} catch (SQLException exception) {
				assertThat(exception.getSQLState()).as("direct scope reads remain denied").isEqualTo("42501");
			}
		}
	}

	private static void assertProvisionerScopeExists(java.sql.Connection connection, UUID tenantId, UUID workspaceId,
			boolean expected) throws SQLException {
		try (var statement = connection.prepareStatement(
				"select tenant_management.provisioner_scope_exists(?, ?)")) {
			statement.setObject(1, tenantId);
			statement.setObject(2, workspaceId);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).as("scope helper result for %s/%s", tenantId, workspaceId)
						.isEqualTo(expected);
			}
		}
	}

	private static void insertProvisioningIsolationTenantAndWorkspace(java.sql.Connection connection, UUID tenantId,
			UUID workspaceId) throws SQLException {
		try (var tenantInsert = connection.prepareStatement("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',current_timestamp,current_timestamp)")) {
			tenantInsert.setObject(1, tenantId);
			tenantInsert.setString(2, "Provisioning isolation test");
			tenantInsert.setString(3, "provisioning-" + tenantId);
			tenantInsert.executeUpdate();
		}
		try (var workspaceInsert = connection.prepareStatement("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp)")) {
			workspaceInsert.setObject(1, workspaceId);
			workspaceInsert.setObject(2, tenantId);
			workspaceInsert.setString(3, "Provisioning isolation test");
			workspaceInsert.setString(4, "provisioning-" + workspaceId);
			workspaceInsert.executeUpdate();
		}
	}

	private static void insertProvisioningIsolationTask(java.sql.Connection connection, String table, UUID taskId,
			UUID tenantId, UUID workspaceId) throws SQLException {
		if (!Set.of("tenant_business_database_provisioning_task", "tenant_business_database_workspace_anchor_task").contains(table)) {
			throw new IllegalArgumentException("Unexpected provisioning queue table");
		}
		try (var insert = connection.prepareStatement("insert into tenant_management." + table + "(task_id,tenant_id,workspace_id) values (?,?,?)")) {
			insert.setObject(1, taskId);
			insert.setObject(2, tenantId);
			insert.setObject(3, workspaceId);
			insert.executeUpdate();
		}
	}

	private static void assertProvisioningInsertDenied(java.sql.Connection connection, String table, UUID taskId,
			UUID tenantId, UUID workspaceId) throws SQLException {
		try {
			insertProvisioningIsolationTask(connection, table, taskId, tenantId, workspaceId);
			throw new AssertionError("Runtime cross-scope queue insert unexpectedly succeeded: " + table);
		} catch (SQLException exception) {
			assertThat(exception.getSQLState()).as("RLS rejects cross-scope queue inserts").isEqualTo("42501");
		}
	}

	private static void assertProvisioningQueueCount(java.sql.Connection connection, String table, long expected)
			throws SQLException {
		if (!Set.of("tenant_business_database_provisioning_task", "tenant_business_database_workspace_anchor_task").contains(table)) {
			throw new IllegalArgumentException("Unexpected provisioning queue table");
		}
		try (var statement = connection.createStatement(); var result = statement.executeQuery(
				"select count(*) from tenant_management." + table)) {
			assertThat(result.next()).isTrue();
			assertThat(result.getLong(1)).as("visible rows in %s", table).isEqualTo(expected);
		}
	}

	private static void deleteProvisioningIsolationRows(java.sql.Connection connection, String table, List<UUID> tenants)
			throws SQLException {
		if (!Set.of("tenant_management.tenant_business_database_provisioning_task",
				"tenant_management.tenant_business_database_workspace_anchor_task",
				"tenant_management.workspace", "tenant_management.tenant").contains(table)) {
			throw new IllegalArgumentException("Unexpected provisioning isolation table");
		}
		try (var delete = connection.prepareStatement("delete from " + table + " where "
				+ (table.endsWith(".tenant") ? "id" : "tenant_id") + " in (?, ?, ?)")) {
			for (int index = 0; index < tenants.size(); index++) delete.setObject(index + 1, tenants.get(index));
			delete.executeUpdate();
		}
	}

	private static void assertPurchaseRequestExpiryIndex(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute("set enable_seqscan = off");
			try (var result = statement.executeQuery("explain (costs off) select id from sales.purchase_request where tenant_id = '00000000-0000-0000-0000-000000000001' and workspace_id = '00000000-0000-0000-0000-000000000002' and status in ('SUBMITTED','IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED') and expires_at <= current_timestamp order by expires_at,id limit 100")) {
				StringBuilder plan = new StringBuilder();
				while (result.next()) plan.append(result.getString(1));
				assertThat(plan).contains("ix_purchase_request_expiry_due");
			}
		}
	}

	private static void assertIndexExists(java.sql.Connection connection, String schema, String index) throws SQLException {
		try (var statement = connection.prepareStatement("select exists(select 1 from pg_indexes where schemaname=? and indexname=?)")) {
			statement.setString(1, schema);
			statement.setString(2, index);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).as("index %s.%s", schema, index).isTrue();
			}
		}
	}

	private static void assertTenantWorkspaceUniqueIndex(java.sql.Connection connection) throws SQLException {
		try (var statement = connection.prepareStatement("""
				select indexdef from pg_indexes
				where schemaname='tenant_management' and tablename='workspace'
				  and indexname='uq_tenant_management_workspace_tenant'
				""")) {
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).as("Tenant may own exactly one Workspace at database level").isTrue();
				assertThat(result.getString(1)).contains("CREATE UNIQUE INDEX", "(tenant_id)");
				assertThat(result.next()).as("Tenant/Workspace invariant has one named index").isFalse();
			}
		}
	}

	private static void assertTenantWorkspaceInvariantPreflight() throws Exception {
		String databaseName = "nexa_v84_preflight";
		String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/nexa(?=\\?|$)", "/" + databaseName);
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + databaseName);
		}
		try {
			Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").cleanDisabled(false).target("83").load().migrate();

			UUID tenantId = UUID.randomUUID();
			UUID workspaceA = UUID.randomUUID();
			UUID workspaceB = UUID.randomUUID();
			try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
				try (var insertTenant = connection.prepareStatement("""
						insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at)
						values (?, 'V84 preflight test', ?, 'ACTIVE', current_timestamp, current_timestamp)
						""")) {
					insertTenant.setObject(1, tenantId);
					insertTenant.setString(2, "v84-preflight-" + tenantId);
					insertTenant.executeUpdate();
				}
				try (var insertWorkspace = connection.prepareStatement("""
						insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at)
						values (?, ?, 'V84 preflight workspace', ?, 'ACTIVE', current_timestamp, current_timestamp)
						""")) {
					for (var workspace : List.of(workspaceA, workspaceB)) {
						insertWorkspace.setObject(1, workspace);
						insertWorkspace.setObject(2, tenantId);
						insertWorkspace.setString(3, "v84-workspace-" + workspace);
						insertWorkspace.executeUpdate();
					}
				}
			}

			assertThatThrownBy(() -> Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").cleanDisabled(false).target("84").load().migrate())
					.as("V84 must refuse to enforce Tenant 1:1 Workspace over duplicate existing data")
					.hasStackTraceContaining("V84 cannot enforce Tenant 1:1 Workspace: duplicate workspaces exist");

			try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
					var statement = connection.createStatement()) {
				statement.executeUpdate("delete from tenant_management.workspace where id='" + workspaceB + "'");
			}
			Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").cleanDisabled(false).target("84").load().migrate();
			try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
				assertTenantWorkspaceUniqueIndex(connection);
				assertThatThrownBy(() -> {
					try (var statement = connection.createStatement()) {
						statement.executeUpdate("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values ('"
								+ UUID.randomUUID() + "','" + tenantId + "','duplicate','duplicate-workspace','ACTIVE',current_timestamp,current_timestamp)");
					}
				}).as("database unique index must reject a second Workspace for the same Tenant")
						.hasMessageContaining("uq_tenant_management_workspace_tenant");
			}
		} finally {
			try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
				statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
			}
		}
	}

	private static void assertWorkspaceMembershipTenantForeignKey() throws Exception {
		UUID tenantA = UUID.randomUUID();
		UUID tenantB = UUID.randomUUID();
		UUID workspaceA = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		try (var connection = POSTGRES.createConnection("")) {
			insertParentScopedRlsTenant(connection, tenantA);
			insertParentScopedRlsTenant(connection, tenantB);
			insertParentScopedRlsWorkspace(connection, workspaceA, tenantA);
			insertParentScopedRlsUser(connection, userId);
			assertSqlState(() -> insertParentScopedRlsMembership(connection, tenantB, membershipId, workspaceA, userId),
					"23503", "membership cannot pair a Tenant with another Tenant's Workspace");
			insertParentScopedRlsMembership(connection, tenantA, membershipId, workspaceA, userId);
			try (var delete = connection.prepareStatement("delete from tenant_management.workspace_membership where id=?")) {
				delete.setObject(1, membershipId);
				delete.executeUpdate();
			}
			deleteParentScopedRlsUsers(connection, userId, userId);
			deleteParentScopedRlsWorkspaces(connection, workspaceA, workspaceA);
			deleteParentScopedRlsTenants(connection, tenantA, tenantB);
		}
	}

	private static void assertWorkspaceMembershipRuntimeTenantScope() throws Exception {
		UUID tenantA = UUID.randomUUID();
		UUID tenantB = UUID.randomUUID();
		UUID workspaceA = UUID.randomUUID();
		UUID workspaceB = UUID.randomUUID();
		UUID identityUser = UUID.randomUUID();
		UUID operationUser = UUID.randomUUID();
		UUID membershipA = UUID.randomUUID();
		UUID membershipB = UUID.randomUUID();
		UUID runtimeInsert = UUID.randomUUID();
		try {
			try (var connection = POSTGRES.createConnection("")) {
				insertParentScopedRlsTenant(connection, tenantA);
				insertParentScopedRlsTenant(connection, tenantB);
				insertParentScopedRlsWorkspace(connection, workspaceA, tenantA);
				insertParentScopedRlsWorkspace(connection, workspaceB, tenantB);
				insertParentScopedRlsUser(connection, identityUser);
				insertParentScopedRlsUser(connection, operationUser);
				insertParentScopedRlsMembership(connection, tenantA, membershipA, workspaceA, identityUser);
				insertParentScopedRlsMembership(connection, tenantB, membershipB, workspaceB, identityUser);
			}

			try (var runtime = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "nexa_runtime", RUNTIME_PASSWORD)) {
				try (var statement = runtime.createStatement(); var result = statement.executeQuery("""
						select has_table_privilege(current_user,
						    'tenant_management.workspace_membership', 'INSERT'), current_user, session_user
						""")) {
					assertThat(result.next()).isTrue();
					assertThat(result.getBoolean(1)).as("runtime has INSERT privilege, so denial must come from RLS").isTrue();
					assertThat(result.getString(2)).isEqualTo("nexa_runtime");
					assertThat(result.getString(3)).isEqualTo("nexa_runtime");
				}

				setRuntimeSetting(runtime, "app.current_tenant_id", "");
				setRuntimeSetting(runtime, "app.current_workspace_id", "");
				setRuntimeSetting(runtime, "app.access_context_user_id", identityUser.toString());
				try (var query = runtime.prepareStatement(
						"select count(*) from tenant_management.workspace_membership where user_id = ?")) {
					query.setObject(1, identityUser);
					try (var result = query.executeQuery()) {
						assertThat(result.next()).isTrue();
						assertThat(result.getLong(1)).as("identity discovery spans the same user's memberships in two Tenants")
								.isEqualTo(2);
					}
				}

				setRuntimeSetting(runtime, "app.current_tenant_id", tenantA.toString());
				setRuntimeSetting(runtime, "app.current_workspace_id", workspaceB.toString());
				assertSqlState(() -> insertParentScopedRlsMembership(runtime, tenantB, runtimeInsert, workspaceB, operationUser),
						"42501", "a matching Workspace under the wrong Tenant context cannot write membership");

				setRuntimeSetting(runtime, "app.current_tenant_id", tenantB.toString());
				insertParentScopedRlsMembership(runtime, tenantB, runtimeInsert, workspaceB, operationUser);
				try (var query = runtime.prepareStatement(
						"select count(*) from tenant_management.workspace_membership where user_id = ?")) {
					query.setObject(1, operationUser);
					try (var result = query.executeQuery()) {
						assertThat(result.next()).isTrue();
						assertThat(result.getLong(1)).as("membership write succeeds under matching Tenant and Workspace")
								.isEqualTo(1);
					}
				}
			}
		} finally {
			try (var connection = POSTGRES.createConnection("")) {
				try (var delete = connection.prepareStatement("delete from tenant_management.workspace_membership where id in (?, ?, ?)")) {
					delete.setObject(1, membershipA);
					delete.setObject(2, membershipB);
					delete.setObject(3, runtimeInsert);
					delete.executeUpdate();
				}
				try (var delete = connection.prepareStatement("delete from iam.user_account where id in (?, ?)")) {
					delete.setObject(1, identityUser);
					delete.setObject(2, operationUser);
					delete.executeUpdate();
				}
				deleteParentScopedRlsWorkspaces(connection, workspaceA, workspaceB);
				deleteParentScopedRlsTenants(connection, tenantA, tenantB);
			}
		}
	}

	private static void setRuntimeSetting(java.sql.Connection connection, String name, String value) throws SQLException {
		try (var set = connection.prepareStatement("select set_config(?, ?, false)")) {
			set.setString(1, name);
			set.setString(2, value);
			set.executeQuery().close();
		}
	}

	private static void assertCompositeMembershipForeignKeys(java.sql.Connection connection) throws SQLException {
		assertConstraintContains(connection, "notifications", "push_subscription", "fk_push_subscription_recipient",
				"FOREIGN KEY (workspace_id, recipient_membership_id)");
		assertConstraintContains(connection, "notifications", "push_subscription_command_idempotency",
				"fk_push_subscription_idempotency_actor", "FOREIGN KEY (workspace_id, actor_membership_id)");
		assertConstraintContains(connection, "tenant_management", "organization_registration", "fk_organization_registration_scope",
				"FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace(tenant_id, id)");
		assertConstraintContains(connection, "tenant_management", "organization_registration",
				"ck_organization_registration_tenant_workspace_pair", "CHECK");
		assertConstraintContains(connection, "tenant_management", "membership_admin_event", "fk_membership_event_scope",
				"FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace(tenant_id, id)");
		assertConstraintContains(connection, "tenant_management", "membership_admin_event", "fk_membership_event_target",
				"FOREIGN KEY (workspace_id, target_membership_id)");
		assertConstraintContains(connection, "tenant_management", "membership_admin_event", "fk_membership_event_actor",
				"FOREIGN KEY (workspace_id, actor_membership_id)");
	}

	private static void assertTenantGovernanceScopeMigrationPreflight() throws Exception {
		UUID tenantA = UUID.randomUUID();
		UUID tenantB = UUID.randomUUID();
		UUID workspaceA = UUID.randomUUID();
		UUID workspaceB = UUID.randomUUID();
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		UUID membershipA = UUID.randomUUID();
		UUID membershipB = UUID.randomUUID();
		UUID registrationId = UUID.randomUUID();
		UUID validRegistrationId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		UUID validEventId = UUID.randomUUID();
		try {
			try (var connection = POSTGRES.createConnection("")) {
				insertParentScopedRlsTenant(connection, tenantA);
				insertParentScopedRlsTenant(connection, tenantB);
				insertParentScopedRlsWorkspace(connection, workspaceA, tenantA);
				insertParentScopedRlsWorkspace(connection, workspaceB, tenantB);
				insertParentScopedRlsUser(connection, userA);
				insertParentScopedRlsUser(connection, userB);
				insertParentScopedRlsMembershipBeforeV174(connection, membershipA, workspaceA, userA);
				insertParentScopedRlsMembershipBeforeV174(connection, membershipB, workspaceB, userB);
				insertGovernanceRegistration(connection, validRegistrationId, "ACTIVE", tenantA, workspaceA, userA);
				insertGovernanceRegistration(connection, registrationId, "ACTIVE", tenantA, workspaceB, userA);
			}
			assertTenantGovernanceMigrationFailsWith(
					"V171 cannot enforce organization registration Tenant/Workspace scope: unpaired or mismatched scope exists");
			try (var connection = POSTGRES.createConnection("")) {
				deleteGovernanceRegistration(connection, registrationId);
				insertGovernanceRegistration(connection, registrationId, "PENDING_ACTIVATION", tenantA, null, userA);
			}
			assertTenantGovernanceMigrationFailsWith(
					"V171 cannot enforce organization registration Tenant/Workspace scope: unpaired or mismatched scope exists");
			try (var connection = POSTGRES.createConnection("")) {
				deleteGovernanceRegistration(connection, registrationId);
				insertMembershipAdminEvent(connection, eventId, tenantA, workspaceA, membershipA, membershipB);
			}
			assertTenantGovernanceMigrationFailsWith(
					"V171 cannot enforce membership administration event Tenant/Workspace scope: mismatched scope or membership exists");
			try (var connection = POSTGRES.createConnection("")) {
				deleteMembershipAdminEvent(connection, eventId);
				insertMembershipAdminEvent(connection, validEventId, tenantA, workspaceA, membershipA, membershipA);
			}

			Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
					.locations("classpath:db/migration").cleanDisabled(false).target("171").load().migrate();
			try (var connection = POSTGRES.createConnection("")) {
				assertCompositeMembershipForeignKeys(connection);
				assertTenantGovernanceScopeRlsAndReferencePrivileges(connection);
				assertGovernanceScopeInsertRejected(connection, registrationId, "ACTIVE", tenantA, workspaceB, userA,
						"23503", "registration Tenant/Workspace mismatch is rejected");
				assertGovernanceScopeInsertRejected(connection, registrationId, "PENDING_ACTIVATION", tenantA, null, userA,
						"23514", "registration scope cannot be half-null");
				assertGovernanceScopeInsertRejected(connection, registrationId, "ACTIVE", null, null, userA,
						"23514", "ACTIVE registration retains its existing required scope invariant");
				assertMembershipAdminEventInsertRejected(connection, eventId, tenantA, workspaceA, membershipA, membershipB,
						"23503", "event actor must belong to the event Workspace");
			}
		} finally {
			try (var connection = POSTGRES.createConnection("")) {
				deleteMembershipAdminEvent(connection, eventId);
				deleteMembershipAdminEvent(connection, validEventId);
				deleteGovernanceRegistration(connection, registrationId);
				deleteGovernanceRegistration(connection, validRegistrationId);
				deleteGovernanceMemberships(connection, membershipA, membershipB);
				deleteParentScopedRlsUsers(connection, userA, userB);
				deleteParentScopedRlsWorkspaces(connection, workspaceA, workspaceB);
				deleteParentScopedRlsTenants(connection, tenantA, tenantB);
			}
		}
	}

	private static void assertTenantGovernanceMigrationFailsWith(String expectedMessage) {
		assertThatThrownBy(() -> Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").cleanDisabled(false).target("171").load().migrate())
				.as("V171 preflight rejects existing governance scope inconsistencies")
				.hasStackTraceContaining(expectedMessage);
	}

	private static void insertGovernanceRegistration(java.sql.Connection connection, UUID id, String status,
			UUID tenantId, UUID workspaceId, UUID founderUserId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.organization_registration
				    (id, legal_name, display_name, normalized_legal_name, operation_category,
				     storage_site_name, storage_site_address, founder_email, founder_display_name,
				     workspace_name, workspace_slug, reference_plan, terms_version, terms_accepted_at,
				     status, status_token_hash, tenant_id, workspace_id, activated_founder_user_id,
				     created_at, updated_at, version)
				values (?, 'Scope Fixture', 'Scope Fixture', 'scope fixture', 'B2B',
				        'Scope Site', 'Lima', 'scope-fixture@example.test', 'Scope Founder',
				        'Scope Workspace', ?, 'Starter', 'test-v1', current_timestamp,
				        ?, repeat('a', 64), ?, ?, ?, current_timestamp, current_timestamp, 0)
				""")) {
			insert.setObject(1, id);
			insert.setString(2, "scope-" + id);
			insert.setString(3, status);
			insert.setObject(4, tenantId);
			insert.setObject(5, workspaceId);
			insert.setObject(6, founderUserId);
			insert.executeUpdate();
		}
	}

	private static void insertMembershipAdminEvent(java.sql.Connection connection, UUID eventId, UUID tenantId,
			UUID workspaceId, UUID targetMembershipId, UUID actorMembershipId) throws SQLException {
		try (var insert = connection.prepareStatement("""
				insert into tenant_management.membership_admin_event
				    (id, event_type, tenant_id, workspace_id, target_membership_id, actor_membership_id,
				     correlation_id, occurred_at)
				values (?, 'MEMBERSHIP_CHANGED', ?, ?, ?, ?, 'scope-fixture', current_timestamp)
				""")) {
			insert.setObject(1, eventId);
			insert.setObject(2, tenantId);
			insert.setObject(3, workspaceId);
			insert.setObject(4, targetMembershipId);
			insert.setObject(5, actorMembershipId);
			insert.executeUpdate();
		}
	}

	private static void deleteGovernanceRegistration(java.sql.Connection connection, UUID id) throws SQLException {
		try (var delete = connection.prepareStatement("delete from tenant_management.organization_registration where id=?")) {
			delete.setObject(1, id);
			delete.executeUpdate();
		}
	}

	private static void deleteMembershipAdminEvent(java.sql.Connection connection, UUID id) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute("alter table tenant_management.membership_admin_event disable trigger membership_admin_event_append_only");
			try (var delete = connection.prepareStatement("delete from tenant_management.membership_admin_event where id=?")) {
				delete.setObject(1, id);
				delete.executeUpdate();
			} finally {
				statement.execute("alter table tenant_management.membership_admin_event enable trigger membership_admin_event_append_only");
			}
		}
	}

	private static void deleteGovernanceMemberships(java.sql.Connection connection, UUID membershipA,
			UUID membershipB) throws SQLException {
		try (var delete = connection.prepareStatement("delete from tenant_management.workspace_membership where id in (?, ?)")) {
			delete.setObject(1, membershipA);
			delete.setObject(2, membershipB);
			delete.executeUpdate();
		}
	}

	private static void deleteParentScopedRlsUsers(java.sql.Connection connection, UUID userA, UUID userB)
			throws SQLException {
		try (var delete = connection.prepareStatement("delete from iam.user_account where id in (?, ?)")) {
			delete.setObject(1, userA);
			delete.setObject(2, userB);
			delete.executeUpdate();
		}
	}

	private static void deleteParentScopedRlsWorkspaces(java.sql.Connection connection, UUID workspaceA,
			UUID workspaceB) throws SQLException {
		try (var delete = connection.prepareStatement("delete from tenant_management.workspace where id in (?, ?)")) {
			delete.setObject(1, workspaceA);
			delete.setObject(2, workspaceB);
			delete.executeUpdate();
		}
	}

	private static void deleteParentScopedRlsTenants(java.sql.Connection connection, UUID tenantA, UUID tenantB)
			throws SQLException {
		try (var delete = connection.prepareStatement("delete from tenant_management.tenant where id in (?, ?)")) {
			delete.setObject(1, tenantA);
			delete.setObject(2, tenantB);
			delete.executeUpdate();
		}
	}

	private static void assertGovernanceScopeInsertRejected(java.sql.Connection connection, UUID id, String status,
			UUID tenantId, UUID workspaceId, UUID founderUserId, String sqlState, String description) throws SQLException {
		assertSqlState(() -> insertGovernanceRegistration(connection, id, status, tenantId, workspaceId, founderUserId),
				sqlState, description);
	}

	private static void assertMembershipAdminEventInsertRejected(java.sql.Connection connection, UUID eventId,
			UUID tenantId, UUID workspaceId, UUID targetMembershipId, UUID actorMembershipId, String sqlState,
			String description) throws SQLException {
		assertSqlState(() -> insertMembershipAdminEvent(connection, eventId, tenantId, workspaceId,
				targetMembershipId, actorMembershipId), sqlState, description);
	}

	private static void assertTenantGovernanceScopeRlsAndReferencePrivileges(java.sql.Connection connection)
			throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("""
				select (select relrowsecurity and relforcerowsecurity from pg_class
			          where oid='tenant_management.organization_registration'::regclass),
			       (select relrowsecurity and relforcerowsecurity from pg_class
			          where oid='tenant_management.membership_admin_event'::regclass),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'tenant_id', 'REFERENCES'),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'id', 'REFERENCES'),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'workspace_id', 'REFERENCES'),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'id', 'REFERENCES'),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace', 'slug', 'REFERENCES'),
			       has_column_privilege('nexa_migrator', 'tenant_management.workspace_membership', 'user_id', 'REFERENCES'),
			       has_column_privilege('nexa_runtime', 'tenant_management.workspace', 'tenant_id', 'REFERENCES'),
			       has_column_privilege('nexa_runtime', 'tenant_management.workspace', 'id', 'REFERENCES'),
			       has_column_privilege('nexa_runtime', 'tenant_management.workspace_membership', 'workspace_id', 'REFERENCES'),
			       has_column_privilege('nexa_runtime', 'tenant_management.workspace_membership', 'id', 'REFERENCES')
				""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean(1)).as("organization registration keeps forced RLS enabled").isTrue();
			assertThat(result.getBoolean(2)).as("membership administration events keep forced RLS enabled").isTrue();
			assertThat(result.getBoolean(3)).as("migrator can reference Workspace tenant key").isTrue();
			assertThat(result.getBoolean(4)).as("migrator can reference Workspace identity key").isTrue();
			assertThat(result.getBoolean(5)).as("migrator can reference Membership Workspace key").isTrue();
			assertThat(result.getBoolean(6)).as("migrator can reference Membership identity key").isTrue();
			assertThat(result.getBoolean(7)).as("migrator has no unrelated Workspace REFERENCES grant").isFalse();
			assertThat(result.getBoolean(8)).as("migrator has no unrelated Membership REFERENCES grant").isFalse();
			for (int column = 9; column <= 12; column++) {
				assertThat(result.getBoolean(column)).as("nexa_runtime receives no V171 REFERENCES grants").isFalse();
			}
		}
	}

	private static void assertSqlState(SqlAction action, String sqlState, String description) throws SQLException {
		try {
			action.execute();
			throw new AssertionError(description + "; insert unexpectedly succeeded");
		} catch (SQLException exception) {
			assertThat(exception.getSQLState()).as(description).isEqualTo(sqlState);
		}
	}

	@FunctionalInterface
	private interface SqlAction {
		void execute() throws SQLException;
	}

	private static void assertConstraintContains(java.sql.Connection connection, String schema, String table,
												 String constraint, String expected) throws SQLException {
		try (var statement = connection.prepareStatement(
				"select pg_get_constraintdef(c.oid) from pg_constraint c join pg_class r on r.oid=c.conrelid "
						+ "join pg_namespace n on n.oid=r.relnamespace where n.nspname=? and r.relname=? and c.conname=?")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			statement.setString(3, constraint);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).as("constraint %s.%s.%s exists", schema, table, constraint).isTrue();
				assertThat(result.getString(1)).contains(expected);
			}
		}
	}

	private static Set<String> indexColumns(java.sql.Connection connection, String schema, String index) throws SQLException {
		try (var statement = connection.prepareStatement("select a.attname from pg_index i join pg_class c on c.oid=i.indexrelid join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey) join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname=? order by array_position(i.indkey,a.attnum)")) {
			statement.setString(1, schema);
			statement.setString(2, index);
			try (var result = statement.executeQuery()) {
				var values = new java.util.LinkedHashSet<String>();
				while (result.next()) values.add(result.getString(1));
				return values;
			}
		}
	}
}
