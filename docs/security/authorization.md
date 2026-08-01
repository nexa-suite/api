# Authorization

Nexa applies defense in depth. Spring Security protects request paths and method security; Application use cases assert the permission through `CurrentAccessContext`. Angular guards only shape navigation and user experience.

`catalog:read` is required for `GET /api/v1/catalog-items` and its detail route. `TENANT_ADMIN`, `COMPANY_OWNER`, `SALES`, `WAREHOUSE`, `LOGISTICS` and `BUYER` can receive that permission under the canonical role policy. Platform accepts internal roles only. Portal accepts `BUYER` only.

Authenticated requests with missing or invalid credentials return `401`. Authenticated requests with an invalid membership context, wrong surface or missing permission return `403` with a stable Problem Detail code. The Catalog aggregate does not contain security checks; authorization stays at Application and Presentation boundaries.

## Fixed role matrix

| Role | Surface | Authority | Explicit boundary |
|---|---|---|---|
| `TENANT_ADMIN` | Platform | tenant/workspace administration, IAM user administration and internal reads | no system-operator activation; cannot remove the final active Tenant Admin |
| `COMPANY_OWNER` | Platform | read-only executive visibility across tenant, sales, warehouse and logistics | no technical role assignment or operational writes |
| `SALES` | Platform | commercial workflows | no warehouse/logistics administration |
| `WAREHOUSE` | Platform | inventory and warehouse workflows | no IAM or tenant administration |
| `LOGISTICS` | Platform | dispatch, tracking and delivery workflows | no IAM or tenant administration |
| `BUYER` | Portal | own client-account, catalog, request, order and tracking capabilities | no Platform routes or internal membership data |

The system-operator boundary is an internal activation capability and is not a Workspace Membership role.
