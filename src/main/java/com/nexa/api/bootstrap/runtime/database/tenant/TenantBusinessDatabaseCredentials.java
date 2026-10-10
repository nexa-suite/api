package com.nexa.api.bootstrap.runtime.database.tenant;

import java.util.Objects;

/** Connection material resolved from one Tenant business-database secret reference. */
public final class TenantBusinessDatabaseCredentials {
	private final String jdbcUrl;
	private final String username;
	private final String password;

	public TenantBusinessDatabaseCredentials(String jdbcUrl, String username, String password) {
		this.jdbcUrl = required(jdbcUrl, "JDBC URL");
		this.username = required(username, "database username");
		this.password = required(password, "database password");
		if (!this.jdbcUrl.startsWith("jdbc:postgresql:")) {
			throw new IllegalArgumentException("Tenant business databases must use PostgreSQL");
		}
	}

	public String jdbcUrl() { return jdbcUrl; }
	public String username() { return username; }
	public String password() { return password; }

	@Override
	public String toString() {
		return "TenantBusinessDatabaseCredentials[jdbcUrl=<redacted>, username=<redacted>, password=<redacted>]";
	}

	private static String required(String value, String label) {
		String result = Objects.requireNonNull(value, label + " is required").strip();
		if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
		return result;
	}
}
