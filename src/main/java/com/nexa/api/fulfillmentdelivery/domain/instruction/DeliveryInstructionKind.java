package com.nexa.api.fulfillmentdelivery.domain.instruction;

/** Operational delivery instruction classes accepted for V1. */
public enum DeliveryInstructionKind {
    NORMAL,
    COLD_CHAIN,
    ACCESS_RESTRICTION,
    SPECIAL_UNLOADING,
    CUSTOMER_SAFETY,
    GOODS_HANDLING;

    public boolean isCritical() {
        return this != NORMAL;
    }
}
