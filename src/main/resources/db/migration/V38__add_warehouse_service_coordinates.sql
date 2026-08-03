ALTER TABLE warehouse.warehouse_service_configuration
    ADD COLUMN IF NOT EXISTS latitude NUMERIC(10,7),
    ADD COLUMN IF NOT EXISTS longitude NUMERIC(10,7);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_warehouse_service_configuration_coordinates'
          AND conrelid = 'warehouse.warehouse_service_configuration'::regclass
    ) THEN
        ALTER TABLE warehouse.warehouse_service_configuration
            ADD CONSTRAINT ck_warehouse_service_configuration_coordinates
            CHECK ((latitude IS NULL AND longitude IS NULL)
                OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));
    END IF;
END $$;
