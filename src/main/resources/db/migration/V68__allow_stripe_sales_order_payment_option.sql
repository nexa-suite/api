-- CARD_STRIPE is a valid buyer payment preference and must survive Sales
-- approval/conversion into the canonical sales_order snapshot.
ALTER TABLE sales.sales_order
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option;

ALTER TABLE sales.sales_order
    ADD CONSTRAINT ck_sales_order_payment_option_v2
    CHECK (payment_option IS NULL OR payment_option IN (
        'CREDIT_LINE', 'BANK_TRANSFER', 'CARD_STRIPE', 'CASH', 'CASH_ON_DELIVERY'
    ));
