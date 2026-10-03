-- Preserve exact sample identity without changing coordinate retention or historical facts.
-- NULL is intentional for earlier samples whose original nanosecond payload is unavailable.
ALTER TABLE logistics.driver_coordinate
    ADD COLUMN request_hash VARCHAR(64),
    ADD CONSTRAINT ck_driver_coordinate_request_hash
        CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$');
