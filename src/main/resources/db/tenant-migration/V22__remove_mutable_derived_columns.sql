-- Tenant V3 projects the V91 generated values. Preserve that published
-- baseline and remove the mutable derived columns with a forward migration.
ALTER TABLE payments.receivable
    DROP COLUMN outstanding_amount,
    DROP COLUMN overpayment_amount;

ALTER TABLE logistics.fulfillment_line
    DROP COLUMN planned_quantity,
    DROP COLUMN remaining_quantity;
