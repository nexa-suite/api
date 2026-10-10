package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
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

class LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProviderTests {
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	@TempDir
	Path temporaryDirectory;

	@Test
	void resolvesSeparateWriterCredentialsOnlyForMatchingTenantDatabaseIdentity() throws IOException {
		TenantId tenantId = TenantId.random();
		UUID databaseIdentity = UUID.randomUUID();
		Path tenantDirectory = tenantDirectory(tenantId, databaseIdentity, 5547);
		writeWriterCredentials(tenantDirectory.resolve("policy-snapshot-writer.properties"),
				"jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business", "nexa_policy_snapshot_writer",
				"tenant-policy-snapshot-writer-test-secret");

		TenantBusinessDatabaseCredentials credentials = provider().requireWriterCredentials(tenantId, databaseIdentity);

		assertThat(credentials.jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business");
		assertThat(credentials.username()).isEqualTo("nexa_policy_snapshot_writer");
		assertThat(credentials.password()).isEqualTo("tenant-policy-snapshot-writer-test-secret");
		assertThat(credentials.toString()).doesNotContain("tenant-policy-snapshot-writer-test-secret");
	}

	@Test
	void rejectsMismatchedIdentityAndRuntimeOrMigratorCredentialReuse() throws IOException {
		TenantId tenantId = TenantId.random();
		UUID databaseIdentity = UUID.randomUUID();
		Path tenantDirectory = tenantDirectory(tenantId, databaseIdentity, 5547);
		Path credentials = tenantDirectory.resolve("policy-snapshot-writer.properties");
		writeWriterCredentials(credentials, "jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"nexa_policy_snapshot_writer", "secret");

		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, UUID.randomUUID()))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");

		writeWriterCredentials(credentials, "jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"nexa_runtime", "runtime-secret");
		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");

		writeWriterCredentials(credentials, "jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"nexa_migrator", "migrator-secret");
		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");
	}

	@Test
	void rejectsRemoteAndDifferentLocalTenantTargets() throws IOException {
		TenantId tenantId = TenantId.random();
		UUID databaseIdentity = UUID.randomUUID();
		Path tenantDirectory = tenantDirectory(tenantId, databaseIdentity, 5547);
		Path credentials = tenantDirectory.resolve("policy-snapshot-writer.properties");
		writeWriterCredentials(credentials, "jdbc:postgresql://db.example.test:5432/nexa_tenant_business",
				"nexa_policy_snapshot_writer", "secret");

		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");

		writeWriterCredentials(credentials, "jdbc:postgresql://127.0.0.1:5548/nexa_tenant_business",
				"nexa_policy_snapshot_writer", "secret");
		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");
	}

	@Test
	void rejectsDuplicateKeysLoosePermissionsAndSymlinkedWriterFiles() throws IOException {
		TenantId tenantId = TenantId.random();
		UUID databaseIdentity = UUID.randomUUID();
		Path tenantDirectory = tenantDirectory(tenantId, databaseIdentity, 5547);
		Path credentials = tenantDirectory.resolve("policy-snapshot-writer.properties");
		Files.writeString(credentials, "jdbc-url=jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business\n"
				+ "username=nexa_policy_snapshot_writer\nusername=nexa_policy_snapshot_writer\npassword=secret\n");
		Files.setPosixFilePermissions(credentials, PRIVATE_FILE);

		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");

		writeWriterCredentials(credentials, "jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"nexa_policy_snapshot_writer", "secret");
		Files.setPosixFilePermissions(credentials, EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");

		Files.delete(credentials);
		Path outside = temporaryDirectory.resolve("outside.properties");
		writeWriterCredentials(outside, "jdbc:postgresql://127.0.0.1:5547/nexa_tenant_business",
				"nexa_policy_snapshot_writer", "secret");
		Files.createSymbolicLink(credentials, outside);
		assertThatThrownBy(() -> provider().requireWriterCredentials(tenantId, databaseIdentity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class)
				.hasMessageContaining("writer credentials are unavailable");
	}

	private LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider provider() {
		return new LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(
				temporaryDirectory.resolve("tenant-databases"));
	}

	private Path tenantDirectory(TenantId tenantId, UUID databaseIdentity, int port) throws IOException {
		Path root = temporaryDirectory.resolve("tenant-databases");
		if (!Files.exists(root)) Files.createDirectory(root);
		Files.setPosixFilePermissions(root, PRIVATE_DIRECTORY);
		Path tenantDirectory = root.resolve(tenantId.toString());
		Files.createDirectory(tenantDirectory);
		Files.setPosixFilePermissions(tenantDirectory, PRIVATE_DIRECTORY);
		Files.writeString(tenantDirectory.resolve("state.properties"), "tenant-id=" + tenantId
				+ "\nworkspace-id=" + UUID.randomUUID() + "\ndatabase-identity=" + databaseIdentity
				+ "\ncredential-secret-reference=local-tenant-db:" + UUID.randomUUID()
				+ "\ndatabase-port=" + port + "\n");
		Files.setPosixFilePermissions(tenantDirectory.resolve("state.properties"), PRIVATE_FILE);
		return tenantDirectory;
	}

	private static void writeWriterCredentials(Path file, String jdbcUrl, String username, String password)
			throws IOException {
		Files.writeString(file, "jdbc-url=" + jdbcUrl + "\nusername=" + username + "\npassword=" + password + "\n");
		Files.setPosixFilePermissions(file, PRIVATE_FILE);
	}
}
