# Operations contract closure

## Baseline and scope

The local implementation continues from API commit `6af902a2a3b2de3966264dda64bda8b11309f89d` on `feature/w4-backend-contract-closure`. It extends the accepted Operations requirements with server contracts; it does not add bounded contexts or a separate Mobile backend. Client development continues on `feature/w4-mobile-contract-closure`.

The accepted business authority is the Blueprint Owner closure of 2026-10-01, including the direct correction restricting communication to Sales and Buyer. Academic documentation describes evidence and requirements; it cannot authorize business state or replace the canonical model.

## First vertical increment: delivery instructions

MOB-US-063 starts with operational Dispatch instructions and assigned-Driver reading and critical acknowledgment in BC-06 Fulfillment & Delivery. BC-01 supplies current membership and capabilities. Warehouse authorization remains supplied through BC-05 public contracts. Technical HTTP composition does not become a domain owner.

Instruction content and revisions are durable business facts. Normal instructions do not require acknowledgment. Cold-chain, access restriction, special unloading, customer safety and goods handling instructions are critical and require acknowledgment of the current revision before a new delivery execution begins. Publication, acknowledgment and attempt start must serialize against the same Delivery to prevent stale acknowledgment from authorizing execution.

The assigned Driver receives only current operational instructions, not pricing, credit or unrelated Customer details. Assignment revocation and terminal Delivery state revoke operational instruction access. An acknowledgment records the server-authorized actor, instruction revision and timestamp. Client-local pending commands remain non-authoritative and require explicit same-key recovery after uncertain outcomes.

Customer/Buyer instruction authoring, externally received instructions recorded by Sales with truthful provenance, and their accepted edit window remain separate coverage to add; operational instruction support does not claim these are complete. Chat never updates instructions implicitly.

## Subsequent increments

| Story | Canonical responsibility | Required implementation boundary |
| --- | --- | --- |
| MOB-US-005 | Owning Inventory or Fulfillment/Delivery process; BC-11 records facts | Classify operational exceptions, preserve responsible actor and lifecycle, distinguish reporting from resolution authority. |
| MOB-US-029 | BC-06 delivery projection with BC-01/BC-02 authority | Operational workday location only, own-Delivery Buyer visibility, permission loss handling and bounded retention. Exact raw-location retention remains an explicit Privacy/Security/Data Governance decision. |
| MOB-US-030 | Authorized Customer/Buyer relationship and referenced business workflow | Driver-to-Buyer communication is excluded by the direct Owner correction. Sales/Buyer communication needs its own canonical ownership and correction-policy contract before message mutations. |
| MOB-US-059 / 060 | BC-06; BC-05 supplies stock restrictions | Same-origin compatible loads, manual stop order/history, Dispatch confirmation and explicit assigned Driver whole-load acceptance. No external 3PL access or optimizer is implied. |
| MOB-US-073 | BC-06 or BC-05 owns the originating reading; BC-05 owns affected stock | Real excursion photo and immutable evidence, attributable exception and preventive affected-quantity HOLD in the required atomic boundary. No automated final disposition. |

These are implementation obligations, not completed features, deployment claims or a redefinition of aggregate ownership. The existing manual thermal guards remain fail-closed until the full excursion linkage exists.

## Evidence boundary

Focused checks are recorded with each implementation increment. The existing local APK and executable API represent the preceding integrated baseline until rebuilt with new changes. They are not public deployment, physical-device validation, Product Acceptance or production-readiness evidence. No release, tag, push or merge is performed by this continuation.
