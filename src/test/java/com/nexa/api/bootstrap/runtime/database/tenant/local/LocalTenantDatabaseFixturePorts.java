package com.nexa.api.bootstrap.runtime.database.tenant.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Checked explicit ports for disposable local-database fixtures; not an atomic reservation. */
final class LocalTenantDatabaseFixturePorts {
    private static final int MIN = 1024;
    private static final int MAX = 65535;
    private static final int COUNT = MAX - MIN + 1;
    private final Set<Integer> used = new HashSet<>();
    private int next = 20000;

    static int[] readEphemeralRange(Path file) throws IOException {
        // Read a complete line from the virtual kernel file before validating its bounds.
        try (var reader = Files.newBufferedReader(file)) {
            String line = reader.readLine();
            String[] bounds = line == null ? new String[0] : line.trim().split("\\s+");
            if (bounds.length != 2) throw new IOException("Expected two host ephemeral port bounds");
            try {
                int first = Integer.parseInt(bounds[0]);
                int last = Integer.parseInt(bounds[1]);
                if (first < 1 || last > MAX || first > last) {
                    throw new IOException("Invalid host ephemeral port bounds");
                }
                return new int[]{first, last};
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid host ephemeral port bounds", invalid);
            }
        }
    }

    synchronized int choose(int ephemeralFirst, int ephemeralLast, Set<Integer> published, PortProbe probe)
            throws IOException {
        if (ephemeralFirst < 1 || ephemeralLast > MAX || ephemeralFirst > ephemeralLast) {
            throw new IllegalArgumentException("Invalid host ephemeral port range");
        }
        int checked = 0;
        int rejected = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int offset = 0; offset < COUNT; offset++) {
                int candidate = MIN + (next - MIN + offset) % COUNT;
                if (pass == 0 && candidate >= ephemeralFirst && candidate <= ephemeralLast) continue;
                if (used.contains(candidate) || published.contains(candidate)) continue;
                checked++;
                if (!probe.available(candidate)) {
                    rejected++;
                    continue;
                }
                used.add(candidate);
                next = candidate == MAX ? MIN : candidate + 1;
                return candidate;
            }
        }
        throw new IOException("No checked explicit fixture port is available in " + MIN + ".." + MAX
                + "; host ephemeral range=" + ephemeralFirst + ".." + ephemeralLast
                + "; Docker published ports=" + published.size() + "; fixture ports=" + used.size()
                + "; checked=" + checked + "; bind rejections=" + rejected);
    }

    @FunctionalInterface
    interface PortProbe {
        boolean available(int port) throws IOException;
    }
}
