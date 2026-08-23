package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresMigrationTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	@Test
	void flywayCreatesOnlyTheModernIdentityAndTenantSchemasWithRequiredTables() throws Exception {
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			statement.execute("create role nexa_runtime");
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).load().migrate();

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
			assertThat(schemas(connection)).containsExactlyInAnyOrder("iam", "tenant_management", "sales", "integration", "warehouse", "logistics", "catalog_management", "reference_data", "notifications", "audit", "business_documents", "payments");
			assertThat(tables(connection, "iam")).containsExactlyInAnyOrder("user_account", "password_credential", "refresh_session", "authentication_failure", "password_reset_request", "security_audit_event", "password_reset_throttle_bucket", "security_notification_outbox", "system_operator_throttle_bucket", "workspace_preview_throttle_bucket", "public_contact_request", "public_contact_throttle_bucket");
			assertThat(tables(connection, "tenant_management")).containsExactlyInAnyOrder("tenant", "workspace", "workspace_membership", "membership_admin_event", "membership_role_assignment", "organization_registration", "organization_settings", "workspace_settings", "regional_settings", "unit_preferences", "operational_settings", "notification_preference", "tenant_security_settings", "custom_field_definition", "reference_plan_assignment", "organization_invitation", "organization_invitation_role", "organization_invitation_idempotency", "workspace_creation_idempotency", "permission_definition", "role_definition", "role_permission", "membership_role_definition", "membership_authorization_state");
			assertThat(tables(connection, "sales")).containsExactlyInAnyOrder("client_account", "client_account_membership", "client_account_address", "purchase_request", "purchase_request_line", "purchase_request_event", "idempotency_record", "manual_order_idempotency", "sales_order_sequence", "sales_order", "sales_order_line", "sales_order_event", "purchase_request_draft", "purchase_request_draft_line", "purchase_request_draft_destination", "purchase_request_draft_route", "purchase_request_draft_warehouse_selection", "purchase_request_draft_idempotency", "manual_sales_order_draft", "manual_sales_order_draft_line", "manual_sales_order_draft_idempotency", "commercial_commitment", "commercial_commitment_line");
			assertThat(tables(connection, "integration")).containsExactlyInAnyOrder("change_event", "outbox_event", "inbox_event");
			assertThat(tables(connection, "warehouse")).containsExactlyInAnyOrder("warehouse", "storage_zone", "inventory_lot", "stock_movement", "inventory_event", "inventory_reservation", "inventory_reservation_line", "inventory_reservation_allocation", "reservation_shortage", "command_idempotency", "warehouse_service_configuration", "selection_snapshot", "inventory_temperature_evaluation", "inventory_lot_disposition", "safety_stock_policy", "inventory_transfer");
			assertThat(tables(connection, "logistics")).containsExactlyInAnyOrder("dispatch_number_counter", "dispatch_order", "dispatch_event", "command_idempotency", "proof_of_delivery", "temperature_reading", "delivery_incident", "buyer_delivery_tracking", "operational_handoff_note", "delivery_attempt", "delivery_attempt_line", "continuation_delivery", "continuation_delivery_line");
			assertThat(tables(connection, "catalog_management")).containsExactlyInAnyOrder("category", "brand", "product", "product_presentation", "product_asset_reference", "product_visibility", "product_price", "promotion", "promotion_product", "promotion_category", "promotion_client_account", "promotion_rule", "command_idempotency", "seed_import_history", "product_family", "product_variant", "sellable_sku", "sku_price", "promotion_sku");
			assertThat(columns(connection, "catalog_management", "product_variant")).containsExactlyInAnyOrder(
				"id", "tenant_id", "workspace_id", "family_id", "variant_code", "name", "description", "status",
				"version", "created_at", "updated_at");
			assertThat(columns(connection, "catalog_management", "sellable_sku")).contains("variant_id");
			assertThat(tables(connection, "business_documents")).containsExactlyInAnyOrder("object_storage_object", "business_document", "document_generation_request", "evidence_object");
			assertThat(tables(connection, "payments")).containsExactlyInAnyOrder("credit_account", "receivable", "payment", "payment_attempt", "payment_reconciliation_case", "receivable_allocation", "credit_reservation", "stripe_event_inbox", "payment_event");
			assertThat(tables(connection, "reference_data")).containsExactlyInAnyOrder("department", "province", "district", "road_type");
			assertThat(tables(connection, "notifications")).containsExactly("inbox_item");
			assertThat(tables(connection, "audit")).containsExactly("event");
			assertThat(columns(connection, "integration", "outbox_event"))
			.contains("processing_started_at", "lease_until", "claim_token");
			assertThat(columns(connection, "payments", "stripe_event_inbox"))
			.contains("processing_started_at", "lease_until", "claim_token");
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
			assertPurchaseRequestExpiryIndex(connection);
		}
		assertOnlyOneConcurrentOutboxLeaseClaimWins();
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


	private static void assertTenantWorkspaceRls(java.sql.Connection connection) throws SQLException {
		Set<String> expectedTables = Set.of(
				"business_documents.business_document", "business_documents.evidence_object", "business_documents.object_storage_object",
				"notifications.inbox_item",
				"payments.credit_account", "payments.credit_reservation", "payments.payment", "payments.payment_attempt", "payments.payment_event", "payments.payment_reconciliation_case", "payments.receivable", "payments.receivable_allocation",
				"warehouse.warehouse", "warehouse.storage_zone", "warehouse.inventory_lot", "warehouse.stock_movement", "warehouse.inventory_event", "warehouse.inventory_reservation", "warehouse.command_idempotency", "warehouse.warehouse_service_configuration", "warehouse.selection_snapshot", "warehouse.inventory_lot_disposition", "warehouse.inventory_temperature_evaluation",
				"logistics.dispatch_number_counter", "logistics.dispatch_order", "logistics.dispatch_event", "logistics.command_idempotency", "logistics.proof_of_delivery", "logistics.temperature_reading", "logistics.delivery_incident", "logistics.operational_handoff_note", "logistics.delivery_attempt", "logistics.delivery_attempt_line", "logistics.continuation_delivery", "logistics.continuation_delivery_line",
				"warehouse.safety_stock_policy", "warehouse.inventory_transfer",
				"sales.client_account", "sales.client_account_address", "sales.client_account_membership", "sales.commercial_commitment", "sales.commercial_commitment_line", "sales.manual_sales_order_draft", "sales.manual_sales_order_draft_idempotency", "sales.manual_sales_order_draft_line", "sales.purchase_request", "sales.purchase_request_event", "sales.idempotency_record", "sales.purchase_request_draft", "sales.purchase_request_draft_destination", "sales.purchase_request_draft_idempotency", "sales.purchase_request_draft_line", "sales.purchase_request_draft_route", "sales.purchase_request_draft_warehouse_selection", "sales.sales_order", "sales.sales_order_event");
		Set<String> actualTables = new java.util.HashSet<>();
		Set<String> policyTables = new java.util.HashSet<>();
		try (var statement = connection.createStatement(); var result = statement.executeQuery("select n.nspname || '.' || c.relname, p.policyname, p.qual, p.with_check from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_policies p on p.schemaname=n.nspname and p.tablename=c.relname where c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity order by 1")) {
			while (result.next()) {
				String table = result.getString(1);
				actualTables.add(table);
				policyTables.add(table);
				assertThat(result.getString(3)).as("USING policy for " + table).contains("current_setting('app.current_tenant_id'").contains("current_setting('app.current_workspace_id'");
				assertThat(result.getString(4)).as("WITH CHECK policy for " + table).contains("current_setting('app.current_tenant_id'").contains("current_setting('app.current_workspace_id'");
			}
		}
		assertThat(actualTables).as("all forced RLS tables must be explicitly inventoried").containsExactlyInAnyOrderElementsOf(expectedTables);
		assertThat(policyTables).as("all forced RLS tables must have an explicit tenant/workspace policy")
				.containsExactlyInAnyOrderElementsOf(expectedTables);
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
