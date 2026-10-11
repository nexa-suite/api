package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresNormalizationRegressionTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	@Test
	void centralAndTenantMigrationsRemoveOnlyMutableGeneratedBusinessValues() throws Exception {
		createTenantMigrationRolesAndDatabase();

		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		try (var connection = POSTGRES.createConnection("")) {
			assertDerivedColumnsAbsent(connection);
			assertThat(generatedColumns(connection, "payments", "financial_adjustment"))
					.contains("kind");
			assertThat(generatedColumns(connection, "payments", "financial_ledger_entry"))
					.contains("direction");
		}

		String tenantJdbcUrl = POSTGRES.getJdbcUrl().replace("/nexa", "/nexa_tenant");
		Flyway.configure().dataSource(tenantJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/tenant-migration").load().migrate();
		try (var connection = DriverManager.getConnection(tenantJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
			assertDerivedColumnsAbsent(connection);
		}
	}

	private static void createTenantMigrationRolesAndDatabase() throws SQLException {
		try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
			for (String role : Set.of("nexa_runtime", "nexa_policy_snapshot_writer", "nexa_business_documents_worker",
					"nexa_payments_worker", "nexa_business_traceability_worker")) {
				statement.execute("create role " + role);
			}
			statement.execute("create database nexa_tenant");
		}
	}

	private static void assertDerivedColumnsAbsent(Connection connection) throws SQLException {
		assertThat(columnNames(connection, "payments", "receivable"))
				.doesNotContain("outstanding_amount", "overpayment_amount");
		assertThat(columnNames(connection, "logistics", "fulfillment_line"))
				.doesNotContain("planned_quantity", "remaining_quantity");
	}

	private static Set<String> columnNames(Connection connection, String schema, String table) throws SQLException {
		Set<String> columns = new HashSet<>();
		try (var statement = connection.prepareStatement("""
				select column_name
				from information_schema.columns
				where table_schema=? and table_name=?
				""")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (var result = statement.executeQuery()) {
				while (result.next()) columns.add(result.getString(1));
			}
		}
		return columns;
	}

	private static Set<String> generatedColumns(Connection connection, String schema, String table) throws SQLException {
		Set<String> columns = new HashSet<>();
		try (var statement = connection.prepareStatement("""
				select column_name
				from information_schema.columns
				where table_schema=? and table_name=? and is_generated='ALWAYS'
				""")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (var result = statement.executeQuery()) {
				while (result.next()) columns.add(result.getString(1));
			}
		}
		return columns;
	}
}
