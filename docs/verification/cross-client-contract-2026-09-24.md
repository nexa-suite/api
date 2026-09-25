# Cross-client API contract evidence — 2026-09-24

This is an API implementation and consumer inventory. Product scope remains in
Blueprint. The committed OpenAPI snapshot and `OpenApiContractIT` describe the
current server contract; they do not prove that a client can complete a flow.
Web Clients evidence uses commit `22c151da0d9447568970aa7c6a10f48fde79e090`;
Operations Mobile evidence uses `ffd1e5b6b114081f25adc6e8132ef1aac299bf73`.

| Surface | Current consumer | Server contract found | Verification boundary |
| --- | --- | --- | --- |
| Public Website | No Website repository in this workspace | `POST /api/v1/public/contact-requests`; anonymous onboarding draft and submission routes | Public contact tests and isolated API runtime smoke: valid demo request returned 202/`RECEIVED` and persisted one row. Integrated Website smoke unavailable. |
| Internal Platform | `web-clients/apps/platform` is an Angular shell with the shared `@nexa/api` HTTP provider; its route list is empty | `/api/v1` base URL and `PLATFORM` browser surface are configured; API owns session and workspace authorization | Shared HTTP tests passed (9/9 across the library); API authentication, security and OpenAPI tests passed. An integrated Platform business flow is unavailable. Legacy Platform is migration evidence only. |
| Buyer Portal | `web-clients/apps/portal` is an Angular shell with the shared `@nexa/api` HTTP provider; its route list is empty | `/api/v1` base URL and `PORTAL` browser surface are configured; API owns buyer-scoped authorization | Shared HTTP tests passed (9/9 across the library); API authentication, authorization and OpenAPI tests passed. An integrated Portal business flow is unavailable. Legacy Portal is migration evidence only. |
| Operations Mobile | `mobile/apps/operations-android` implements an auth/network foundation | Existing `PLATFORM` native sign-in, refresh, sign-out and session routes | Gateway MockWebServer tests and `:core:auth:testDebugUnitTest :core:network:testDebugUnitTest` passed; API `AuthenticationFlowIT` covers native transport. A live Android-to-API smoke was not run. |
| Buyer Mobile | No Buyer Mobile application | Blueprint maps Buyer Mobile to `PORTAL` plus `NATIVE` transport | No integrated consumer proof. No separate speculative API was added. |

## Native auth shape observed in current code

The Android gateway sends `X-Nexa-Client: NATIVE` and a sign-in body containing
`identifier`, `password`, `workspaceSlug`, and `surface: PLATFORM`. It expects
an access token in the response body and an opaque refresh credential in
`X-Nexa-Refresh-Token`. Refresh sends that credential and
`X-Nexa-Surface: PLATFORM`; session and sign-out use bearer authorization.
`AuthenticationFlowIT` verifies the API's native header transport, refresh
rotation, and absence of a browser cookie on that path. The Android gateway
checks that `/api/v1/session` contains a user, Tenant, Workspace, membership,
and matching surface before treating the context as authorized.

The current Blueprint accepts global Human Identity and a Mobile context
selection direction, but does not yet freeze the complete 0/1/2+ context
transition, pre-context credential, replacement-session behavior, and HTTP
contract. The existing workspace-slug sign-in is current implementation
evidence, not authority to close that Product decision. No candidate context
selection route was added.

## Compatibility and remaining proof

The structural fix did not change public routes or schemas. The full Maven
verification includes `OpenApiContractIT`, and the compatibility script found
no breaking change against `origin/develop`. The new Web shells do not yet
exercise business routes. Website and Buyer Mobile lack runnable consumers
here. A real Operations Mobile smoke and a reviewed, exact access-context
contract remain open before integrated acceptance can be claimed.
