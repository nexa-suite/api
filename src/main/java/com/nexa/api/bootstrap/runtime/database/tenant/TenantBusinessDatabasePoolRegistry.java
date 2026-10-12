package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds all Tenant pools in one router registry and retires changed bindings without interrupting leases. */
final class TenantBusinessDatabasePoolRegistry implements AutoCloseable {
	private final TenantBusinessDatabaseDataSourceFactory factory;
	private final int maxCachedPools;
	private final Map<PoolKey, PoolEntry> available = new LinkedHashMap<>();
	private final Set<PoolEntry> allPools = new LinkedHashSet<>();
	private boolean closed;

	TenantBusinessDatabasePoolRegistry(TenantBusinessDatabaseDataSourceFactory factory, int maxCachedPools) {
		this.factory = Objects.requireNonNull(factory, "Tenant database pool factory is required");
		if (maxCachedPools < 1) throw new IllegalArgumentException("Maximum cached Tenant pool count must be positive");
		this.maxCachedPools = maxCachedPools;
	}

	synchronized Lease acquire(TenantBusinessDatabaseBinding binding) {
		Objects.requireNonNull(binding, "Tenant database binding is required");
		if (closed) throw unavailable("Tenant business database router is closed");

		PoolKey key = new PoolKey(binding.tenantId(), binding.databaseIdentity(), binding.credentialSecretReference());
		retireReboundPools(key);

		PoolEntry entry = available.get(key);
		if (entry != null) {
			entry.activeLeases++;
			moveToMostRecentlyUsed(key, entry);
			return new Lease(this, entry);
		}

		evictIdlePoolsUntilCapacityExists();
		if (allPools.size() >= maxCachedPools) {
			throw unavailable("Tenant business database pool capacity is occupied by active transactions");
		}

		DataSource delegate = Objects.requireNonNull(factory.create(binding),
				"Tenant database pool factory returned no pool");
		if (!(delegate instanceof AutoCloseable)) {
			throw new IllegalStateException("Tenant database pools must be closeable for safe eviction");
		}
		TenantBusinessDatabaseIdentityCheckingDataSource identityChecked =
				new TenantBusinessDatabaseIdentityCheckingDataSource(delegate, binding);
		entry = new PoolEntry(key, identityChecked);
		entry.activeLeases = 1;
		available.put(key, entry);
		allPools.add(entry);
		return new Lease(this, entry);
	}

	private void retireReboundPools(PoolKey currentKey) {
		for (PoolEntry entry : new ArrayList<>(allPools)) {
			if (entry.key.tenantId().equals(currentKey.tenantId()) && !entry.key.equals(currentKey) && !entry.retired) {
				retire(entry);
			}
		}
	}

	synchronized void retireTenant(TenantId tenantId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		for (PoolEntry entry : new ArrayList<>(allPools)) {
			if (entry.key.tenantId().equals(tenantId)) retire(entry);
		}
	}

	private void evictIdlePoolsUntilCapacityExists() {
		while (allPools.size() >= maxCachedPools) {
			PoolEntry idle = available.values().stream().filter(candidate -> candidate.activeLeases == 0)
					.findFirst().orElse(null);
			if (idle == null) return;
			retire(idle);
		}
	}

	private void moveToMostRecentlyUsed(PoolKey key, PoolEntry entry) {
		available.remove(key);
		available.put(key, entry);
	}

	private synchronized void release(PoolEntry entry) {
		if (entry.activeLeases <= 0) throw new IllegalStateException("Tenant database pool lease was released twice");
		entry.activeLeases--;
		if (entry.activeLeases == 0 && (entry.retired || closed)) retire(entry);
	}

	private void retire(PoolEntry entry) {
		entry.retired = true;
		available.remove(entry.key, entry);
		if (entry.activeLeases != 0 || !allPools.remove(entry)) return;
		try {
			((AutoCloseable) entry.dataSource).close();
		} catch (Exception exception) {
			throw new TenantBusinessDatabaseUnavailableException(
					"Could not close a retired Tenant business database pool", exception);
		}
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		List<PoolEntry> entries = new ArrayList<>(allPools);
		available.clear();
		RuntimeException failure = null;
		for (PoolEntry entry : entries) {
			try {
				retire(entry);
			} catch (RuntimeException exception) {
				if (failure == null) failure = exception;
				else failure.addSuppressed(exception);
			}
		}
		if (failure != null) throw failure;
	}

	private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
		return new TenantBusinessDatabaseUnavailableException(message);
	}

	private record PoolKey(TenantId tenantId, UUID databaseIdentity, String credentialSecretReference) { }

	private static final class PoolEntry {
		private final PoolKey key;
		private final TenantBusinessDatabaseIdentityCheckingDataSource dataSource;
		private int activeLeases;
		private boolean retired;

		private PoolEntry(PoolKey key, TenantBusinessDatabaseIdentityCheckingDataSource dataSource) {
			this.key = key;
			this.dataSource = dataSource;
		}
	}

	static final class Lease implements AutoCloseable {
		private final TenantBusinessDatabasePoolRegistry owner;
		private final PoolEntry entry;
		private final AtomicBoolean released = new AtomicBoolean();

		private Lease(TenantBusinessDatabasePoolRegistry owner, PoolEntry entry) {
			this.owner = owner;
			this.entry = entry;
		}

		DataSource dataSource(WorkspaceId workspaceId) {
			if (released.get()) throw new IllegalStateException("Tenant database pool lease is closed");
			return entry.dataSource.forVerifiedWorkspace(workspaceId);
		}

		@Override
		public void close() {
			if (released.compareAndSet(false, true)) owner.release(entry);
		}
	}
}
