-- A Buyer-selected wallet tender is consented on the Purchase Request and
-- carries only an opaque BC-01 identity reference. No identity data is copied.
ALTER TABLE sales.purchase_request
    ADD COLUMN buyer_wallet_beneficiary_identity_id UUID;

ALTER TABLE sales.purchase_request
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option,
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option_v1,
    DROP CONSTRAINT IF EXISTS ck_purchase_request_payment_option_v2;

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_payment_option_v3 CHECK (payment_option IS NULL OR payment_option IN (
        'CREDIT_LINE', 'BANK_TRANSFER', 'CARD_STRIPE', 'CASH', 'CASH_ON_DELIVERY', 'PREPAID', 'IMMEDIATE', 'WALLET'
    )),
    ADD CONSTRAINT ck_purchase_request_wallet_beneficiary CHECK (
        (payment_option = 'WALLET' AND buyer_wallet_beneficiary_identity_id IS NOT NULL)
        OR (payment_option IS DISTINCT FROM 'WALLET' AND buyer_wallet_beneficiary_identity_id IS NULL)
    );

ALTER TABLE sales.sales_order
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option,
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option_v2,
    DROP CONSTRAINT IF EXISTS ck_sales_order_payment_option_v3;

ALTER TABLE sales.sales_order
    ADD CONSTRAINT ck_sales_order_payment_option_v4 CHECK (payment_option IS NULL OR payment_option IN (
        'CREDIT_LINE', 'BANK_TRANSFER', 'CARD_STRIPE', 'CASH', 'CASH_ON_DELIVERY', 'PREPAID', 'IMMEDIATE', 'WALLET'
    )),
    ADD CONSTRAINT ck_sales_order_wallet_tender_origin CHECK (
        payment_option IS DISTINCT FROM 'WALLET' OR origin_type = 'PURCHASE_REQUEST'
    );
