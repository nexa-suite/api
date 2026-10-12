-- V91 remains immutable. Mutable row values with no independent authority are
-- derived in the read model instead of being stored alongside their inputs.
ALTER TABLE payments.receivable
    DROP COLUMN outstanding_amount,
    DROP COLUMN overpayment_amount;

ALTER TABLE logistics.fulfillment_line
    DROP COLUMN planned_quantity,
    DROP COLUMN remaining_quantity;
