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
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).load().migrate();

		try (var connection = POSTGRES.createConnection("")) {
			assertThat(schemas(connection)).containsExactlyInAnyOrder("iam", "tenant_management", "sales", "integration", "warehouse", "logistics");
			assertThat(tables(connection, "iam")).containsExactlyInAnyOrder("user_account", "password_credential", "refresh_session", "authentication_failure");
			assertThat(tables(connection, "tenant_management")).containsExactlyInAnyOrder("tenant", "workspace", "workspace_membership", "membership_admin_event");
			assertThat(tables(connection, "sales")).containsExactlyInAnyOrder("client_account", "client_account_membership", "purchase_request", "purchase_request_line", "purchase_request_event", "idempotency_record", "sales_order_sequence", "sales_order", "sales_order_line", "sales_order_event");
			assertThat(tables(connection, "integration")).containsExactly("change_event");
			assertThat(tables(connection, "warehouse")).containsExactlyInAnyOrder("warehouse", "storage_zone", "inventory_lot", "stock_movement", "inventory_event", "inventory_reservation", "inventory_reservation_line", "inventory_reservation_allocation", "reservation_shortage", "command_idempotency");
			assertThat(tables(connection, "logistics")).containsExactlyInAnyOrder("dispatch_number_counter", "dispatch_order", "dispatch_event", "command_idempotency", "proof_of_delivery", "temperature_reading", "delivery_incident", "buyer_delivery_tracking");
			assertThat(columns(connection, "integration", "change_event")).containsExactlyInAnyOrder(
				"sequence", "event_id", "tenant_id", "workspace_id", "client_account_id", "aggregate_type",
				"aggregate_id", "event_type", "aggregate_version", "public_status", "audiences", "occurred_at", "expires_at");
			assertThat(columns(connection, "iam", "refresh_session")).containsExactlyInAnyOrder(
				"id", "user_id", "membership_id", "surface", "token_hash", "family_id", "created_at", "last_used_at",
				"expires_at", "revoked_at", "family_revoked_at", "replaced_by_session_id", "version");
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
