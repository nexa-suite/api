# Inbound receipt acknowledgement

Implementation: optional `X-Nexa-Received-Quantity` response header on
`POST /api/v1/inventory/inbound-receipts`. The existing 201 status, ETag and
`LotResponse` JSON remain unchanged.

The lot body is a current stock projection. It can differ from the original
received quantity if another operation changed stock before a receipt retry.
Consumers must not treat current `onHand` as immutable receipt evidence.

The header is a base-10 decimal acknowledgement of the original receipt amount.
It is emitted only when the persisted idempotency hash matches the complete
request. The fresh-insert path also checks that the saved idempotency resource
is the new lot. Tenant, Workspace, warehouse grants, transaction and existing
same-payload rules remain in force. The amount must be positive and exactly
representable at the database's four-decimal scale. It is not normalized or
rounded for this acknowledgement.

Legacy idempotency records with empty hashes cannot establish this additional
proof. They omit the header, as do inputs requiring fractional rounding. This
change neither rewrites these records nor changes the existing quantity policy.
The remaining quantity-precision policy requires separate treatment; it is not
silently resolved by a successful HTTP response.

Operations requests only this allowlisted response metadata, validates that it
is a positive decimal equal to the original command, and carries it separately
from current lot stock through the Application result. Malformed or conflicting
metadata produces an unknown outcome. If the header is absent, older-server
compatibility preserves the previous conservative `onHand == requested`
comparison; a mismatch remains unconfirmed. Receipt identity, dates and unit
checks remain required. An uncertain action retains its original command/key.

No Domain rule, schema migration, stock adjustment, request-body contract or
documented grant changed. Existing Application constructors remain compatible;
the new nullable metadata is not exposed in the lot JSON.

Technical evidence: `./mvnw -o -Dmaven.test.skip=true compile` passed in an
isolated source copy. All three live source files matched their pre-edit
SHA-256 baseline before integration. Operations debug APK assembly passed after
the client adaptation. No tests, database mutation, runtime replay experiment,
deployment or Product/System acceptance ran. The OpenAPI snapshot has not been
regenerated; update it with the repository workflow before publication.
