package com.nexa.api.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.STANDALONE, module = "BC-01-tenant-access-governance")
class TenantManagementModuleBootstrapTests {
    @Test
    void moduleContextBootstraps() { assertThat(true).isTrue(); }
}
