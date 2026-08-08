package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresMigrationTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	@Test
	void flywayCreatesOnlyTheModernIdentityAndTenantSchemasWithRequiredTables() throws SQLException {
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
			assertThat(tables(connection, "iam")).containsExactlyInAnyOrder("user_account", "password_credential", "refresh_session", "authentication_failure", "password_reset_request", "security_audit_event", "password_reset_throttle_bucket", "security_notification_outbox", "system_operator_throttle_bucket", "workspace_preview_throttle_bucket");
			assertThat(tables(connection, "tenant_management")).containsExactlyInAnyOrder("tenant", "workspace", "workspace_membership", "membership_admin_event", "membership_role_assignment", "organization_registration", "organization_settings", "workspace_settings", "regional_settings", "unit_preferences", "operational_settings", "notification_preference", "tenant_security_settings", "custom_field_definition", "reference_plan_assignment", "organization_invitation", "organization_invitation_role", "organization_invitation_idempotency", "workspace_creation_idempotency", "permission_definition", "role_definition", "role_permission", "membership_role_definition", "membership_authorization_state");
			assertThat(tables(connection, "sales")).containsExactlyInAnyOrder("client_account", "client_account_membership", "client_account_address", "purchase_request", "purchase_request_line", "purchase_request_event", "idempotency_record", "manual_order_idempotency", "sales_order_sequence", "sales_order", "sales_order_line", "sales_order_event", "purchase_request_draft", "purchase_request_draft_line", "purchase_request_draft_destination", "purchase_request_draft_route", "purchase_request_draft_warehouse_selection", "purchase_request_draft_idempotency", "manual_sales_order_draft", "manual_sales_order_draft_line", "manual_sales_order_draft_idempotency");
			assertThat(tables(connection, "integration")).containsExactlyInAnyOrder("change_event", "outbox_event", "inbox_event");
			assertThat(tables(connection, "warehouse")).containsExactlyInAnyOrder("warehouse", "storage_zone", "inventory_lot", "stock_movement", "inventory_event", "inventory_reservation", "inventory_reservation_line", "inventory_reservation_allocation", "reservation_shortage", "command_idempotency", "warehouse_service_configuration", "selection_snapshot");
			assertThat(tables(connection, "logistics")).containsExactlyInAnyOrder("dispatch_number_counter", "dispatch_order", "dispatch_event", "command_idempotency", "proof_of_delivery", "temperature_reading", "delivery_incident", "buyer_delivery_tracking", "operational_handoff_note");
			assertThat(tables(connection, "catalog_management")).containsExactlyInAnyOrder("category", "brand", "product", "product_presentation", "product_asset_reference", "product_visibility", "product_price", "promotion", "promotion_product", "promotion_category", "promotion_client_account", "promotion_rule", "command_idempotency", "seed_import_history", "product_family", "product_variant", "sellable_sku", "sku_price", "promotion_sku");
			assertThat(columns(connection, "catalog_management", "product_variant")).containsExactlyInAnyOrder(
				"id", "tenant_id", "workspace_id", "family_id", "variant_code", "name", "description", "status",
				"version", "created_at", "updated_at");
			assertThat(columns(connection, "catalog_management", "sellable_sku")).contains("variant_id");
			assertThat(tables(connection, "business_documents")).containsExactlyInAnyOrder("object_storage_object", "business_document", "document_generation_request", "evidence_object");
			assertThat(tables(connection, "payments")).containsExactlyInAnyOrder("credit_account", "receivable", "payment", "payment_attempt", "receivable_allocation", "credit_reservation", "stripe_event_inbox", "payment_event");
			assertThat(tables(connection, "reference_data")).containsExactlyInAnyOrder("department", "province", "district", "road_type");
			assertThat(tables(connection, "notifications")).containsExactly("inbox_item");
			assertThat(tables(connection, "audit")).containsExactly("event");
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
				"operating_hours_end", "order_cutoff_minutes", "thermal_log_required", "version", "updated_at");
			assertThat(columns(connection, "catalog_management", "promotion")).contains("priority");
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
}
