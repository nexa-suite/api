# Inventory lot temperature readings

`GET /api/v1/inventory/lots/{lotId}/temperature-readings` exposes only facts
persisted by BC-05 for the selected lot. The response contains the manual
receiving observation stored on `warehouse.inventory_lot` and a bounded page
of `warehouse.inventory_temperature_evaluation` records. Evaluation pages use
`page` (default `0`) and `size` (default `25`, maximum `100`); items are ordered
by creation time descending, then ID descending. The evaluation page includes
only `source_type=RECEIVING`; `FULFILLMENT` and `STOCK_EVIDENCE` rows are
excluded even though BC-05 persists them, because those observations originate
in fulfillment and stock-evidence workflows.

Warehouse readers need `warehouse.read` and the current active grant for the
lot's Warehouse. A Business Operations Manager needs the dedicated
`inventory.temperature.audit.read` permission and can read lots only from the
active Tenant and Workspace. Missing or inaccessible lots return the existing
not-found response. This route is read-only and does not read logistics
temperature tables or expose a logistics command.

The receiving observation is nullable for a lot without a persisted receiving
temperature. When present, its `valueCelsius`, recording time, actor membership
and evidence object are returned as stored. Receiving evaluation values use
`observed_value`, falling back to the legacy `received_value`; expected bounds,
status, disposition, resolution facts, evidence, affected quantity, actor and
the committed-execution blocking flag come from the same BC-05 evaluation row.

The route is available with the `local` warehouse operations capability.
