# Authorization

Nexa applies defense in depth. Spring Security protects request paths and method security; Application use cases assert the permission through `CurrentAccessContext`. Angular guards only shape navigation and user experience.

`catalog:read` is required for `GET /api/v1/catalog-items` and its detail route. `COMPANY_OWNER`, `SALES`, `WAREHOUSE`, `LOGISTICS` and `BUYER` can receive that permission under the canonical role policy. Platform accepts internal roles only. Portal accepts `BUYER` only.

Authenticated requests with missing or invalid credentials return `401`. Authenticated requests with an invalid membership context, wrong surface or missing permission return `403` with a stable Problem Detail code. The Catalog aggregate does not contain security checks; authorization stays at Application and Presentation boundaries.
