# Cross-client API contract evidence — 2026-09-24

This is an API implementation and consumer inventory. Product scope remains in
Blueprint. The committed OpenAPI snapshot and `OpenApiContractIT` describe the
current server contract; they do not prove that a client can complete a flow.

| Surface | Current consumer | Server contract found | Verification boundary |
| --- | --- | --- | --- |
| Public Website | No Website repository in this workspace | `POST /api/v1/public/contact-requests`; anonymous onboarding draft and submission routes | Public contact tests and isolated API runtime smoke: valid demo request returned 202/`RECEIVED` and persisted one row. Integrated Website smoke unavailable. |
| Internal Platform | `web-clients` has a UI library but no Platform application | `PLATFORM` browser session, bearer API calls, workspace authorization | API authentication, security and OpenAPI tests; new-client smoke unavailable. Legacy Platform is migration evidence only. |
| Buyer Portal | `web-clients` has no Portal application | `PORTAL` browser session and buyer-scoped routes | API authentication, authorization and OpenAPI tests; new-client smoke unavailable. Legacy Portal is migration evidence only. |
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
no breaking change against `origin/develop`. Website, new Web applications,
and Buyer Mobile lack runnable consumers here. A real Operations Mobile smoke
and a reviewed, exact access-context contract remain open before integrated
acceptance can be claimed.
