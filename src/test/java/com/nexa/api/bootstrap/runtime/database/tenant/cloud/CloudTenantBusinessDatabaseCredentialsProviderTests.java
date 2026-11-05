package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudTenantBusinessDatabaseCredentialsProviderTests {
	private static final String TOKEN = "opaque_0123456789abcdef0123456789abcdef";
	private static final String REFERENCE = "cloud-tenant-db:" + TOKEN;
	private static final String URL = "jdbc:postgresql://ep-example-123456.us-east-2.aws.neon.tech:5432/"
			+ "nexa_tenant_business?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require";
	@TempDir Path directory;

	@Test
	void resolvesOnlyTheReferencedPrivateRuntimeSecretIncludingReadOnlyMounts() throws IOException {
		Path file = runtimeFile(REFERENCE, URL, "nexa_runtime");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
		var credentials = runtime().requireCredentials(REFERENCE);
		assertThat(credentials.jdbcUrl()).isEqualTo(URL);
		assertThat(credentials.username()).isEqualTo("nexa_runtime");
		assertThat(credentials.password()).isEqualTo("test-runtime-secret");
		assertThat(credentials.toString()).doesNotContain("test-runtime-secret", "ep-example");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ep-tiny-math-b5fkqcok.us-east-2.aws.neon.tech",
			"ep-tiny-math-b5fkqcok.c-7.us-east-2.aws.neon.tech",
			"ep-example-123456.eastus2.azure.neon.tech", "ep-example-123456.c-2.eastus2.azure.neon.tech" })
	void supportsKnownDirectNeonEndpointFormats(String host) throws IOException {
		String url = URL.replace("ep-example-123456.us-east-2.aws.neon.tech", host);
		runtimeFile(REFERENCE, url, "nexa_runtime");
		assertThat(runtime().requireCredentials(REFERENCE).jdbcUrl()).isEqualTo(url);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "cloud-tenant-db:../../credentials", "local-tenant-db:opaque",
			"cloud-tenant-db:too-short", "cloud-tenant-db:unknown_0123456789abcdef0123456789abcdef" })
	void rejectsUnknownMalformedAndLocalReferences(String reference) throws IOException {
		runtimeFile(REFERENCE, URL, "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(reference))
				.isInstanceOf(IllegalStateException.class).hasMessage("Cloud Tenant database credentials are unavailable or unsafe");
	}

	@ParameterizedTest
	@ValueSource(strings = { "nexa_migrator", "neondb_owner", "nexa_policy_snapshot_writer", "postgres" })
	void refusesOtherDatabaseRoles(String role) throws IOException {
		runtimeFile(REFERENCE, URL, role);
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"jdbc:postgresql://127.0.0.1:5432/nexa_tenant_business",
			"jdbc:postgresql://db.example.test:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456-pooler.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456-pooler.c-7.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.us-east-2.aws.neon.tech.evil.test:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.extra.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.extra.c-7.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.c-other.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://nexa_runtime:secret@ep-example-123456.us-east-2.aws.neon.tech:5432/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.us-east-2.aws.neon.tech:5433/nexa_tenant_business",
			"jdbc:postgresql://ep-example-123456.us-east-2.aws.neon.tech:5432/central"
	})
	void refusesWrongHostPortDatabaseOrUserinfoEvenWithValidTls(String jdbcUrl) throws IOException {
		runtimeFile(REFERENCE, jdbcUrl + URL.substring(URL.indexOf('?')), "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesMissingTlsEvenForValidDirectHost() throws IOException {
		runtimeFile(REFERENCE, URL.substring(0, URL.indexOf('?')), "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "sslmode=require", "sslmode=disable", "sslmode=verify-ca", "sslmode=prefer",
			"sslmode=verify-full&sslmode=disable", "sslmode=verify-full&user=neondb_owner",
			"sslmode=verify-full&ssl=false", "sslmode=verify-full&options=-c%20role%3Dnexa_migrator" })
	void refusesTlsDowngradesDuplicateParametersAndAdditionalConnectionOptions(String replacement) throws IOException {
		runtimeFile(REFERENCE, URL.replace("sslmode=verify-full", replacement), "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesUntrustedSslFactoriesOrOptionalChannelBinding() throws IOException {
		runtimeFile(REFERENCE, URL.replace("DefaultJavaSSLFactory", "NonValidatingFactory"), "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
		runtimeFile(REFERENCE, URL.replace("channelBinding=require", "channelBinding=prefer"), "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesMismatchedReferencesDuplicateKeysAndUnexpectedMaterial() throws IOException {
		Path file = runtimeFile("cloud-tenant-db:another_0123456789abcdef0123456789abcdef", URL, "nexa_runtime");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
		runtimeFile(REFERENCE, URL, "nexa_runtime");
		Files.writeString(file, Files.readString(file) + "password=overwritten-secret\n");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
		runtimeFile(REFERENCE, URL, "nexa_runtime");
		Files.writeString(file, Files.readString(file) + "migrator-password=forbidden\n");
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesPublicFilesSymlinksAndWritableDirectories() throws IOException {
		Path file = runtimeFile(REFERENCE, URL, "nexa_runtime");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
		Path target = directory.resolve("outside.properties");
		Files.move(file, target);
		Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
		Files.createSymbolicLink(file, target);
		assertThatThrownBy(() -> runtime().requireCredentials(REFERENCE)).isInstanceOf(IllegalStateException.class);
		Files.delete(file);
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));
		assertThatThrownBy(this::runtime).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resolvesDedicatedWriterOnlyForItsExactTenantAndDatabaseIdentity() throws IOException {
		TenantId tenant = TenantId.random();
		UUID identity = UUID.randomUUID();
		Path file = writerFile(tenant, identity, tenant.toString(), identity.toString(), "nexa_policy_snapshot_writer", URL);
		var writer = writer();
		assertThat(writer.requireWriterCredentials(tenant, identity).username()).isEqualTo("nexa_policy_snapshot_writer");
		assertThat(writer.requireWriterCredentials(tenant, identity).password()).isEqualTo("test-writer-secret");
		assertThatThrownBy(() -> writer.requireWriterCredentials(tenant, UUID.randomUUID()))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
		Files.writeString(file, Files.readString(file).replace(identity.toString(), UUID.randomUUID().toString()));
		assertThatThrownBy(() -> writer.requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
		writerFile(tenant, identity, TenantId.random().toString(), identity.toString(), "nexa_policy_snapshot_writer", URL);
		assertThatThrownBy(() -> writer.requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "nexa_runtime", "nexa_migrator", "neondb_owner" })
	void writerRefusesRuntimeAndPrivilegedRoleReuse(String role) throws IOException {
		TenantId tenant = TenantId.random();
		UUID identity = UUID.randomUUID();
		writerFile(tenant, identity, tenant.toString(), identity.toString(), role, URL);
		assertThatThrownBy(() -> writer().requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
	}

	@Test
	void writerRefusesMissingIdentityUnsafeTlsAndLoosePermissions() throws IOException {
		TenantId tenant = TenantId.random();
		UUID identity = UUID.randomUUID();
		Path file = writerFile(tenant, identity, tenant.toString(), "", "nexa_policy_snapshot_writer", URL);
		assertThatThrownBy(() -> writer().requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
		writerFile(tenant, identity, tenant.toString(), identity.toString(), "nexa_policy_snapshot_writer",
				URL.replace("sslmode=verify-full", "sslmode=require"));
		assertThatThrownBy(() -> writer().requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
		writerFile(tenant, identity, tenant.toString(), identity.toString(), "nexa_policy_snapshot_writer", URL);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
		assertThatThrownBy(() -> writer().requireWriterCredentials(tenant, identity))
				.isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
	}

	private CloudTenantBusinessDatabaseCredentialsProvider runtime() {
		return new CloudTenantBusinessDatabaseCredentialsProvider(directory);
	}

	private CloudTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider writer() {
		return new CloudTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(directory);
	}

	private Path runtimeFile(String reference, String url, String role) throws IOException {
		return write("tenant-runtime-" + TOKEN + ".properties", "credential-secret-reference=" + reference
				+ "\njdbc-url=" + url + "\nusername=" + role + "\npassword=test-runtime-secret\n");
	}

	private Path writerFile(TenantId tenant, UUID identity, String claimedTenant, String claimedIdentity,
			String role, String url) throws IOException {
		return write("tenant-policy-writer-" + tenant + "-" + identity + ".properties", "tenant-id=" + claimedTenant
				+ "\ndatabase-identity=" + claimedIdentity + "\njdbc-url=" + url + "\nusername=" + role
				+ "\npassword=test-writer-secret\n");
	}

	private Path write(String name, String content) throws IOException {
		Path file = directory.resolve(name);
		Files.writeString(file, content);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		return file;
	}
}
