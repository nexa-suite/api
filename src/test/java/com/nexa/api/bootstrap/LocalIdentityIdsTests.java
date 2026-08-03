package com.nexa.api.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalIdentityIdsTests {
	@Test
	void localIdentityKeysAreStableAndScopedByAggregateKind() {
		UUID tenant = LocalIdentityIds.forTenant("ICISA");
		UUID sameTenant = LocalIdentityIds.forTenant("icisa");
		UUID workspace = LocalIdentityIds.forWorkspace(tenant, "ICISA");
		UUID user = LocalIdentityIds.forUser("Carlos.Rios@ICISA.PE");

		assertThat(sameTenant).isEqualTo(tenant);
		assertThat(LocalIdentityIds.forWorkspace(tenant, "icisa")).isEqualTo(workspace);
		assertThat(LocalIdentityIds.forUser("carlos.rios@icisa.pe")).isEqualTo(user);
		assertThat(LocalIdentityIds.forMembership(workspace, user))
				.isNotEqualTo(LocalIdentityIds.forClientAccount(tenant, "buyer-001"));
	}
}
