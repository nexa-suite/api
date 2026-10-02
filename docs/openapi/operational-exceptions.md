# Operational exception coordination

These routes are for members with the current scoped `delivery.exception.read` or
`delivery.exception.coordinate` permission. A case is linked to an immutable typed
Delivery incident. Coordination history does not change the underlying Delivery
outcome, release an execution HOLD, or authorize inventory disposition.

`GET /api/v1/operational-exceptions` returns `{asOf, exceptions}`. Each case
includes its `deliveryId`, current `deliveryVersion`, source/type/severity facts,
status, actor and timestamp facts, current coordination owner, and evidence object
IDs. Use the quoted strong Delivery version as `If-Match` for a mutation.

`GET /api/v1/operational-exceptions/{exceptionId}/assignees` returns only current
eligible targets as `{membershipId, displayName, coordinator, driverReporter}`.
Coordinator eligibility comes from BC-01's current effective permissions. A Driver
reporter is listed only while currently assigned to the Delivery.

Mutations require both `If-Match: "{deliveryVersion}"` and an actor-scoped
`Idempotency-Key`. Exact retries return the original transition with `replayed: true`
and HTTP 200; a new transition returns HTTP 201. Reusing a key with another body is
a conflict.

| Method and path | Request body | Effect |
| --- | --- | --- |
| `POST /api/v1/operational-exceptions/{id}/claims` | `{reason}` | Claims an OPEN coordination case for the current actor. |
| `POST /api/v1/operational-exceptions/{id}/assignments` | `{responsibleMembershipId, reason}` | Assigns or reassigns to a currently eligible coordinator or still-assigned Driver reporter. |
| `POST /api/v1/operational-exceptions/{id}/follow-ups` | `{reason, note?}` | Appends a follow-up for the current coordination owner. |
| `POST /api/v1/operational-exceptions/{id}/resolutions` | `{reason}` | Resolves only DELAY or INCOMPLETE_INSTRUCTION warnings after an authoritative Delivery outcome. |
| `POST /api/v1/operational-exceptions/{id}/closures` | `{reason}` | Closes a resolved warning; does not close BLOCKING or CRITICAL cases. |

Each mutation returns `{deliveryId, deliveryVersion, exception, replayed}` and a
strong ETag for the new or replayed Delivery version. Unknown or out-of-scope cases
return 404, stale versions return 412, and invalid transitions, ineligible targets,
or missing authoritative outcomes return 409.
