package com.nexa.api.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.context.annotation.Import(ModuleTokenCodecFixture.class)
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.STANDALONE, module = "BC-01-tenant-access-governance")
class TenantManagementModuleBootstrapTests {
    @Test
    void moduleContextBootstraps() { assertThat(true).isTrue(); }
}
