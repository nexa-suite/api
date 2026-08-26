package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchSortTests {
    @Test
    void usesServerOwnedDefaultAndExplicitSortSql() {
        assertThat(DispatchSort.parse(null).sql())
                .isEqualTo("d.updated_at desc,d.id desc");
        assertThat(DispatchSort.parse("priority,desc").sql())
                .isEqualTo("d.priority desc,d.delivery_window_start desc nulls last,d.id desc");
        assertThat(DispatchSort.parse("dispatchNumber,asc").sql())
                .isEqualTo("d.dispatch_number asc,d.id asc");
    }

    @Test
    void rejectsUnknownKeysDirectionsAndAdditionalComponents() {
        assertInvalid("foo,desc");
        assertInvalid("priority,invalid");
        assertInvalid("priority,desc,extra");
        assertInvalid("priority,desc nulls first");
        assertInvalid("priority desc; drop table logistics.dispatch_order");
        assertInvalid("updatedAt,(select pg_sleep(5))");
        assertInvalid("status,desc,--");
    }

    @Test
    void rejectsEmptySortComponents() {
        assertInvalid(",desc");
        assertInvalid("priority,");
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> DispatchSort.parse(value))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("INVALID_INVENTORY_SORT");
    }
}
