package com.nexa.api.bootstrap.runtime.database.tenant.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTenantDatabaseFixturePortsTests {
    @Test
    void readsCompleteTabSeparatedKernelBounds(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("range");
        Files.writeString(file, "32768\t60999\n");
        assertThat(LocalTenantDatabaseFixturePorts.readEphemeralRange(file)).containsExactly(32768, 60999);
    }

    @Test
    void incompleteKernelBoundsFailWithExplicitDiagnostic(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("range");
        Files.writeString(file, "3\n");
        assertThatThrownBy(() -> LocalTenantDatabaseFixturePorts.readEphemeralRange(file))
                .isInstanceOf(IOException.class).hasMessage("Expected two host ephemeral port bounds");
    }

    @Test
    void prefersCheckedPortOutsideHostEphemeralRange() throws Exception {
        int port = new LocalTenantDatabaseFixturePorts().choose(15000, 60999, Set.of(), candidate -> true);
        assertThat(port).isEqualTo(61000);
    }

    @Test
    void fullEphemeralRangeStillAllowsCheckedExplicitFixturePort() throws Exception {
        var probed = new ArrayList<Integer>();
        int port = new LocalTenantDatabaseFixturePorts().choose(1024, 65535, Set.of(), candidate -> {
            probed.add(candidate);
            return true;
        });
        assertThat(port).isEqualTo(20000);
        assertThat(probed).containsExactly(20000);
    }

    @Test
    void fallsBackWhenNoPreferredPortCanBind() throws Exception {
        int port = new LocalTenantDatabaseFixturePorts().choose(20000, 20001, Set.of(), candidate -> candidate == 20000);
        assertThat(port).isEqualTo(20000);
    }

    @Test
    void rejectsMalformedRangeWithoutProbing() {
        assertThatThrownBy(() -> new LocalTenantDatabaseFixturePorts().choose(60000, 1024, Set.of(), candidate -> {
            throw new AssertionError("Invalid range must not be probed");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid host ephemeral port range");
    }

    @Test
    void excludesDockerPublicationsAndPortsThatCannotBind() throws Exception {
        var probed = new ArrayList<Integer>();
        int port = new LocalTenantDatabaseFixturePorts().choose(32768, 60999, Set.of(20000), candidate -> {
            probed.add(candidate);
            return candidate != 20001;
        });
        assertThat(port).isEqualTo(20002);
        assertThat(probed).containsExactly(20001, 20002);
    }

    @Test
    void doesNotReusePortWhenEarlierFixtureHasBeenStopped() throws Exception {
        var ports = new LocalTenantDatabaseFixturePorts();
        assertThat(ports.choose(1024, 65535, Set.of(), candidate -> true)).isEqualTo(20000);
        assertThat(ports.choose(1024, 65535, Set.of(), candidate -> true)).isEqualTo(20001);
    }

    @Test
    void includesBothValidExplicitPortBoundaries() throws Exception {
        var ports = new LocalTenantDatabaseFixturePorts();
        assertThat(ports.choose(1024, 65534, Set.of(), candidate -> true)).isEqualTo(65535);
        assertThat(ports.choose(1025, 65535, Set.of(), candidate -> true)).isEqualTo(1024);
    }

    @Test
    void exhaustionReportsActualHostRangeWithoutRetryingProvisioning() {
        assertThatThrownBy(() -> new LocalTenantDatabaseFixturePorts()
                .choose(1024, 65535, Set.of(20000), candidate -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("host ephemeral range=1024..65535")
                .hasMessageContaining("Docker published ports=1");
    }

    @Test
    void propagatesUnexpectedProbeFailure() {
        assertThatThrownBy(() -> new LocalTenantDatabaseFixturePorts().choose(32768, 60999, Set.of(), candidate -> {
            throw new IOException("Probe unavailable");
        })).isInstanceOf(IOException.class).hasMessage("Probe unavailable");
    }
}
