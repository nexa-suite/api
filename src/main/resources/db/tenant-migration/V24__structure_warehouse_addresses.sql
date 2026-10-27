-- Additive structured warehouse addresses; unknown legacy values remain NULL.
ALTER TABLE warehouse.warehouse
    ADD COLUMN address_country_code varchar(2),
    ADD COLUMN address_department_code varchar(40),
    ADD COLUMN address_province_code varchar(40),
    ADD COLUMN address_district_code varchar(40),
    ADD COLUMN address_city varchar(160),
    ADD COLUMN address_road_type varchar(40),
    ADD COLUMN address_street_name varchar(200),
    ADD COLUMN address_street_number varchar(32),
    ADD COLUMN address_interior varchar(80),
    ADD COLUMN address_postal_code varchar(32),
    ADD COLUMN address_reference varchar(500),
    ADD CONSTRAINT ck_warehouse_address_details CHECK (
        address_country_code IS NULL OR (
            address_country_code ~ '^[A-Z]{2}$'
            AND nullif(btrim(address_city),'') IS NOT NULL
            AND nullif(btrim(address_road_type),'') IS NOT NULL
            AND nullif(btrim(address_street_name),'') IS NOT NULL
            AND nullif(btrim(address_street_number),'') IS NOT NULL
            AND (address_country_code <> 'PE' OR (
                address_department_code ~ '^[0-9]{2}$'
                AND address_province_code ~ '^[0-9]{4}$'
                AND address_district_code ~ '^[0-9]{6}$'
                AND left(address_province_code,2)=address_department_code
                AND left(address_district_code,4)=address_province_code
                AND address_department_code IS NOT NULL
                AND address_province_code IS NOT NULL
                AND address_district_code IS NOT NULL
            ))
        )
    );
