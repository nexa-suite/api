package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Strict reads of mounted cloud secrets; rejected material never appears in error details. */
final class CloudTenantDatabaseSecretFiles {
	private static final Set<PosixFilePermission> PRIVATE_READ = Set.of(PosixFilePermission.OWNER_READ);
	private static final Set<PosixFilePermission> PRIVATE_READ_WRITE = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
	private static final Map<String, String> TLS_PARAMETERS = Map.of(
			"sslmode", "verify-full", "sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory",
			"channelBinding", "require");

	private CloudTenantDatabaseSecretFiles() { }

	static Map<String, String> read(Path directory, String fileName, Set<String> requiredKeys) {
		try {
			requireDirectory(directory);
			Path file = directory.resolve(fileName);
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(file) > 16_384) throw unavailable();
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
			if (!permissions.equals(PRIVATE_READ) && !permissions.equals(PRIVATE_READ_WRITE)) throw unavailable();
			Properties properties = new Properties() {
				@Override
				public synchronized Object put(Object key, Object value) {
					if (containsKey(key)) throw unavailable();
					return super.put(key, value);
				}
			};
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
					Reader reader = Channels.newReader(channel, StandardCharsets.UTF_8)) {
				properties.load(reader);
			}
			if (!properties.stringPropertyNames().equals(requiredKeys)) throw unavailable();
			Map<String, String> result = new HashMap<>();
			for (String key : requiredKeys) {
				String value = properties.getProperty(key);
				if (value == null || value.isBlank()) throw unavailable();
				result.put(key, value);
			}
			return Map.copyOf(result);
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	static void requireDirectory(Path directory) {
		try {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
			if (permissions.contains(PosixFilePermission.GROUP_WRITE)
					|| permissions.contains(PosixFilePermission.OTHERS_WRITE)) throw unavailable();
		} catch (IOException | UnsupportedOperationException exception) {
			throw unavailable();
		}
	}

	static void requireNeonJdbcUrl(String jdbcUrl) {
		try {
			if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw unavailable();
			URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
			String host = uri.getHost();
			if (host == null || !host.matches("ep-[a-z0-9-]+\\.(c-[0-9]+\\.)?[a-z0-9-]+\\.(aws|azure)\\.neon\\.tech")
					|| host.substring(0, host.indexOf('.')).endsWith("-pooler")
					|| uri.getPort() != 5432 || uri.getUserInfo() != null || uri.getRawFragment() != null
					|| !"/nexa_tenant_business".equals(uri.getRawPath()) || uri.getRawQuery() == null) {
				throw unavailable();
			}
			Map<String, String> parameters = new HashMap<>();
			for (String pair : uri.getRawQuery().split("&", -1)) {
				String[] entry = pair.split("=", -1);
				if (entry.length != 2 || parameters.putIfAbsent(entry[0], entry[1]) != null) throw unavailable();
			}
			if (!parameters.equals(TLS_PARAMETERS)) throw unavailable();
		} catch (IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	static IllegalStateException unavailable() {
		return new IllegalStateException("Cloud Tenant database credentials are unavailable or unsafe");
	}
}
