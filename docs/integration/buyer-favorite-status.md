# Buyer favorite status

Implementation: additive `GET /api/v1/buyer/favorites/{sellableSkuId}`.
Runtime and Product acceptance remain pending.

The response is `{ "sellableSkuId": "<UUID>", "saved": true }`, with
`Cache-Control: no-store`. `saved` describes only the current verified Buyer
membership's stored association. It does not assert current Catalog visibility,
price or availability. An absent association returns `saved: false`; failed
authorization or an unavailable service never means an absent association.

The use case requires the Buyer role, `buyer.sales.read`, `catalog.read`, an
active Buyer relationship and a routed Tenant database transaction. The query
binds Tenant, Workspace, Buyer membership and SKU. Callers cannot supply another
membership or broaden their current access. Existing local-profile and
`nexa.tenant-business.catalog-favorites.enabled` gates remain in effect.

Buyer Mobile reads this endpoint when opening a product and while that route
remains visible. Returning from background refreshes the current state. An
unconfirmed state offers retry; save/remove is presented only after a confirmed
read or mutation. Uncertain mutations retain their original idempotent action.
List pagination cannot establish that an unlisted SKU is absent.

No migration or mutation contract changed. Technical evidence:
`./mvnw -o -Dmaven.test.skip=true compile` passed in an isolated source copy.
Edited live files matched their pre-edit SHA-256 snapshot before integration.
No tests, live HTTP requests, deployment or acceptance ran. Regenerate the
checked-in OpenAPI snapshot with the repository workflow before publication.
