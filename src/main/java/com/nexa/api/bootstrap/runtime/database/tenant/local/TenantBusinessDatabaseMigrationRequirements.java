package com.nexa.api.bootstrap.runtime.database.tenant.local;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Schema migrations required by the Tenant business capabilities enabled in this build. */
public final class TenantBusinessDatabaseMigrationRequirements {
	private static final String MANIFEST_RESOURCE = "db/tenant-migration/tenant-business-migration-requirements.properties";
	private static final String ACTIVE_CAPABILITIES_KEY = "activeCapabilities";
	private static final String CAPABILITY_KEY_PREFIX = "capability.";
	private static final String MIGRATION_ASSET_KEY_PREFIX = "migrationAsset.";

	private final Set<String> activeCapabilities;
	private final Set<String> requiredVersions;
	private final Map<String, String> migrationAssets;
	private final ClassLoader classLoader;
	private final byte[] manifestContent;

	private TenantBusinessDatabaseMigrationRequirements(Set<String> activeCapabilities,
			Set<String> requiredVersions, Map<String, String> migrationAssets, ClassLoader classLoader,
			byte[] manifestContent) {
		this.activeCapabilities = Collections.unmodifiableSet(new LinkedHashSet<>(activeCapabilities));
		TreeSet<String> sortedVersions = new TreeSet<>(java.util.Comparator.comparingInt(Integer::parseInt));
		sortedVersions.addAll(requiredVersions);
		this.requiredVersions = Collections.unmodifiableSet(sortedVersions);
		this.migrationAssets = Collections.unmodifiableMap(new LinkedHashMap<>(migrationAssets));
		this.classLoader = classLoader;
		this.manifestContent = manifestContent.clone();
	}

	public static TenantBusinessDatabaseMigrationRequirements load() {
		return load(TenantBusinessDatabaseMigrationRequirements.class.getClassLoader());
	}

	static TenantBusinessDatabaseMigrationRequirements load(ClassLoader classLoader) {
		Properties manifest = new Properties();
		byte[] manifestContent;
		try (InputStream input = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
			if (input == null) throw readinessFailure("Tenant migration requirements manifest is missing");
			manifestContent = input.readAllBytes();
			manifest.load(new java.io.ByteArrayInputStream(manifestContent));
		} catch (IOException exception) {
			throw readinessFailure("Tenant migration requirements manifest cannot be read");
		}

		Set<String> activeCapabilities = csv(required(manifest, ACTIVE_CAPABILITIES_KEY));
		if (activeCapabilities.isEmpty()) throw readinessFailure("Tenant migration manifest has no active capabilities");

		Set<String> versions = new TreeSet<>(java.util.Comparator.comparingInt(Integer::parseInt));
		for (String capability : activeCapabilities) {
			String value = required(manifest, CAPABILITY_KEY_PREFIX + capability);
			Set<String> capabilityVersions = csv(value);
			if (capabilityVersions.isEmpty()) {
				throw readinessFailure("Tenant migration manifest has no schema versions for capability " + capability);
			}
			for (String version : capabilityVersions) {
				if (!version.matches("[1-9][0-9]*")) {
					throw readinessFailure("Tenant migration manifest has an invalid schema version for capability " + capability);
				}
				versions.add(version);
			}
		}

		Map<String, String> migrationAssets = new LinkedHashMap<>();
		for (String version : versions) {
			String asset = required(manifest, MIGRATION_ASSET_KEY_PREFIX + version);
			if (!asset.matches("db/tenant-migration/V" + version + "__[A-Za-z0-9_-]+\\.sql")) {
				throw readinessFailure("Tenant migration manifest has an invalid SQL asset for V" + version);
			}
			migrationAssets.put(version, asset);
		}
		return new TenantBusinessDatabaseMigrationRequirements(activeCapabilities, versions, migrationAssets,
				classLoader, manifestContent);
	}

	public Set<String> activeCapabilities() {
		return activeCapabilities;
	}

	public Set<String> requiredVersions() {
		return requiredVersions;
	}

	public void verifyRequiredSqlAssets() {
		verifyRequiredSqlAssets(classLoader);
	}

	void verifyRequiredSqlAssets(ClassLoader resourceLoader) {
		for (Map.Entry<String, String> migration : migrationAssets.entrySet()) {
			URL asset = resourceLoader.getResource(migration.getValue());
			if (asset == null) {
				throw readinessFailure("Required Tenant migration V" + migration.getKey()
						+ " SQL asset is missing from the application artifact");
			}
		}
	}

	public void verifyRequiredMigrations(Set<String> appliedVersions) {
		Set<String> missingVersions = new TreeSet<>(requiredVersions);
		missingVersions.removeAll(appliedVersions);
		if (!missingVersions.isEmpty()) {
			throw readinessFailure("Tenant database cannot become READY; required capability migrations are missing: "
					+ missingVersions.stream().map(version -> "V" + version).toList());
		}
	}

	/** Stable evidence for the exact active capability manifest and its immutable Flyway SQL assets. */
	public String schemaManifestDigest() {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
		updateField(digest, "manifest", manifestContent);
		migrationAssets.entrySet().stream()
				.sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey())))
				.forEach(entry -> {
					updateField(digest, "version", entry.getKey().getBytes(StandardCharsets.UTF_8));
					updateField(digest, "asset", entry.getValue().getBytes(StandardCharsets.UTF_8));
					try (InputStream asset = classLoader.getResourceAsStream(entry.getValue())) {
						if (asset == null) {
							throw readinessFailure("Required Tenant migration V" + entry.getKey()
									+ " SQL asset is missing from the application artifact");
						}
						updateField(digest, "sql", asset.readAllBytes());
					} catch (IOException exception) {
						throw readinessFailure("Required Tenant migration V" + entry.getKey()
								+ " SQL asset cannot be read from the application artifact");
					}
				});
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void updateField(MessageDigest digest, String name, byte[] value) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
		digest.update(nameBytes);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
		digest.update(value);
	}

	private static String required(Properties manifest, String key) {
		String value = manifest.getProperty(key);
		if (value == null || value.isBlank()) throw readinessFailure("Tenant migration manifest is missing " + key);
		return value.trim();
	}

	private static Set<String> csv(String value) {
		Set<String> entries = new LinkedHashSet<>();
		Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(entry -> !entry.isEmpty())
				.forEach(entries::add);
		return entries;
	}

	private static LocalTenantBusinessDatabaseProvisioningCli.SafeReadinessException readinessFailure(String message) {
		return new LocalTenantBusinessDatabaseProvisioningCli.SafeReadinessException(message);
	}
}
