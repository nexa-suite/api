-- A single fulfillment line can be allocated across multiple FEFO lots.
-- Preserve one legacy logical result row while allowing one immutable row per
-- physical allocation binding for the Mobile picking contract. Existing rows
-- are retained; only the uniqueness projection is evolved forward.
ALTER TABLE logistics.picking_result_line
    DROP CONSTRAINT uq_picking_result_line;

CREATE UNIQUE INDEX uq_picking_result_line_legacy_v17
    ON logistics.picking_result_line (tenant_id, workspace_id, picking_result_id, fulfillment_line_id)
    WHERE physical_allocation_line_id IS NULL;

CREATE UNIQUE INDEX uq_picking_result_line_physical_v17
    ON logistics.picking_result_line (tenant_id, workspace_id, picking_result_id, physical_allocation_line_id)
    WHERE physical_allocation_line_id IS NOT NULL;
