package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.local.LocalTenantBusinessDatabaseCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTenantBusinessDatabaseCredentialsProviderTests {
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	@TempDir
	Path temporaryDirectory;

	@Test
	void resolvesPrivateRuntimeCredentialsUsingTenantNetworkAliasAndHostLoopback() throws IOException {
		Path tenantDirectory = tenantDirectory(UUID.randomUUID());
		String reference = reference();
		writeCredentials(tenantDirectory.resolve("credentials.properties"), reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business", apiJdbcUrl(tenantDirectory),
				"nexa_runtime", "tenant-runtime-secret");

		TenantBusinessDatabaseCredentials credentials = provider().requireCredentials(reference);
		TenantBusinessDatabaseCredentials hostCredentials = provider().requireHostCredentials(reference);

		assertThat(credentials.jdbcUrl()).isEqualTo(apiJdbcUrl(tenantDirectory));
		assertThat(hostCredentials.jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business");
		assertThat(credentials.username()).isEqualTo("nexa_runtime");
		assertThat(credentials.password()).isEqualTo("tenant-runtime-secret");
		assertThat(credentials.toString()).doesNotContain("tenant-runtime-secret");
	}

	@Test
	void rejectsMissingAndDuplicatedSecretReferences() throws IOException {
		Path first = tenantDirectory(UUID.randomUUID());
		Path second = tenantDirectory(UUID.randomUUID());
		String reference = reference();
		writeCredentials(first.resolve("credentials.properties"), reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business", apiJdbcUrl(first), "nexa_runtime", "one");

		assertThatThrownBy(() -> provider().requireCredentials(reference()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No unique local Tenant runtime credentials");

		writeCredentials(second.resolve("credentials.properties"), reference,
				"jdbc:postgresql://127.0.0.1:5548/nexa_tenant_business", apiJdbcUrl(second), "nexa_runtime", "two");

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("reference is duplicated");
	}

	@Test
	void rejectsCredentialFilesWithDuplicateKeysOrLoosePermissions() throws IOException {
		Path tenantDirectory = tenantDirectory(UUID.randomUUID());
		Path credentials = tenantDirectory.resolve("credentials.properties");
		String reference = reference();
		Files.writeString(credentials, "credential-secret-reference=" + reference + "\n"
				+ "credential-secret-reference=" + reference + "\n"
				+ "jdbc-url=jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business\n"
				+ "api-jdbc-url=" + apiJdbcUrl(tenantDirectory) + "\n"
				+ "username=nexa_runtime\npassword=secret\n");
		Files.setPosixFilePermissions(credentials, PRIVATE_FILE);

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("duplicate key");

		writeCredentials(credentials, reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business", apiJdbcUrl(tenantDirectory),
				"nexa_runtime", "secret");
		Files.setPosixFilePermissions(credentials, EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("owner read/write only");
	}

	@Test
	void rejectsRemoteTargetsAndNonRuntimeCredentials() throws IOException {
		Path tenantDirectory = tenantDirectory(UUID.randomUUID());
		String reference = reference();
		Path credentials = tenantDirectory.resolve("credentials.properties");
		writeCredentials(credentials, reference,
				"jdbc:postgresql://db.example.test:5432/nexa_tenant_business", apiJdbcUrl(tenantDirectory),
				"nexa_runtime", "secret");

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("loopback Tenant database");

		writeCredentials(credentials, reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business", apiJdbcUrl(tenantDirectory),
				"nexa_migrator", "secret");

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("runtime role");

		writeCredentials(credentials, reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"jdbc:postgresql://db.example.test:5432/nexa_tenant_business", "nexa_runtime", "secret");

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("UUID-bound Tenant network alias");
	}

	@Test
	void rejectsSymlinkedTenantCredentialFiles() throws IOException {
		Path tenantDirectory = tenantDirectory(UUID.randomUUID());
		Path outside = temporaryDirectory.resolve("outside.properties");
		String reference = reference();
		writeCredentials(outside, reference,
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"jdbc:postgresql://tenant-db-00000000-0000-4000-8000-000000000000:5432/nexa_tenant_business",
				"nexa_runtime", "secret");
		Files.createSymbolicLink(tenantDirectory.resolve("credentials.properties"), outside);

		assertThatThrownBy(() -> provider().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("regular file");
	}

	private LocalTenantBusinessDatabaseCredentialsProvider provider() {
		return new LocalTenantBusinessDatabaseCredentialsProvider(temporaryDirectory.resolve("tenant-databases"));
	}

	private Path tenantDirectory(UUID tenantId) throws IOException {
		Path root = temporaryDirectory.resolve("tenant-databases");
		if (!Files.exists(root)) Files.createDirectory(root);
		Files.setPosixFilePermissions(root, PRIVATE_DIRECTORY);
		Path tenantDirectory = root.resolve(tenantId.toString());
		Files.createDirectory(tenantDirectory);
		Files.setPosixFilePermissions(tenantDirectory, PRIVATE_DIRECTORY);
		return tenantDirectory;
	}

	private void writeCredentials(Path file, String reference, String hostJdbcUrl, String apiJdbcUrl,
			String username, String password)
			throws IOException {
		Files.writeString(file, "credential-secret-reference=" + reference + "\n"
				+ "jdbc-url=" + hostJdbcUrl + "\napi-jdbc-url=" + apiJdbcUrl + "\n"
				+ "username=" + username + "\npassword=" + password + "\n");
		Files.setPosixFilePermissions(file, PRIVATE_FILE);
	}

	private static String apiJdbcUrl(Path tenantDirectory) {
		return "jdbc:postgresql://tenant-db-" + tenantDirectory.getFileName()
				+ ":5432/nexa_tenant_business";
	}

	private static String reference() {
		return "local-tenant-db:opaque-" + UUID.randomUUID();
	}
}
