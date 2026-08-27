package com.nexa.api.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.STANDALONE, module = "BC-03-catalog-commercial-policy")
class CatalogModuleBootstrapTests {
    @Test
    void moduleContextBootstraps() { assertThat(true).isTrue(); }
}
