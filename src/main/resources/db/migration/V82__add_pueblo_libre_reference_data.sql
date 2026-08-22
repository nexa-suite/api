INSERT INTO reference_data.district(code, department_code, province_code, name, source, dataset_version)
VALUES ('150121', '15', '1501', 'Pueblo Libre', 'INEI UBIGEO reference', '2C-local-2')
ON CONFLICT (code) DO UPDATE SET name=excluded.name, source=excluded.source, dataset_version=excluded.dataset_version;
