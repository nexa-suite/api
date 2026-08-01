ALTER TABLE sales.idempotency_record
    ADD COLUMN request_hash VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE sales.idempotency_record
    ADD CONSTRAINT ck_sales_idempotency_request_hash
        CHECK (request_hash = '' OR request_hash ~ '^[0-9a-fA-F]{64}$');
