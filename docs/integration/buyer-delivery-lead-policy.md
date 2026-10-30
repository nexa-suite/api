# Buyer delivery lead policy

`GET /api/v1/buyer/delivery-lead-policy` requires an authenticated, current
`PORTAL` context with a `BUYER` membership and `buyer.sales.read`. Tenant and
Workspace are resolved only from the verified request context. Response has
exactly three fields:

- `minimumDeliveryLeadBusinessDays`: fixed global value `5`.
- `minimumAllowedDeliveryDate`: earliest eligible ISO date, calculated from
  the day after the current date in Lima.
- `timezone`: `America/Lima`.

Business days are Monday through Friday. No holiday calendar applies. The rule
is global and has no Tenant or Workspace configuration. Draft creation and
delivery-date changes for Buyer Purchase Requests, Direct Orders, and Manual
Sales Orders validate against the rule. Draft submission validates again so a
date cannot become eligible only because it passed when the draft was created.
Idempotent retries of already-created orders return their original result.
Responses use `Cache-Control: no-store`.
