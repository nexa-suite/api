package com.nexa.api.shared.infrastructure;

import com.nexa.api.shared.application.changefeed.ChangeFeedCapacityException;
import com.nexa.api.shared.application.changefeed.ChangeFeedConnectionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeFeedConnectionLimitIT {
    @Test void registryRejectsTheThirdSessionAndReleasesCapacity() {
        var registry = new ChangeFeedConnectionRegistry(2, 2, 2, 2);
        try (var first = registry.reserve("s1", "u1", "w1"); var second = registry.reserve("s2", "u2", "w1")) {
            assertThatThrownBy(() -> registry.reserve("s3", "u3", "w1")).isInstanceOf(ChangeFeedCapacityException.class);
        }
        try (var released = registry.reserve("s3", "u3", "w1")) { }
    }
}
