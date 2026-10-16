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

## Functional data increment — 2026-10-15

Current local changes extend existing contracts without changing server business ownership:

- `GET /catalog-items/filter-options?kind=brand|category` provides paginated, scoped references for catalog selectors. Product name, category, family and variety remain distinct. New SKUs receive stable catalog identifiers; Buyer reads require activation, visibility and a current price.
- Own-profile updates support first/last names and a complete personal-document tuple. Omitting new fields preserves stored values. Business RUC remains separate from personal identification.
- Warehouse location updates support structured address details, validate road-type references and the Peruvian department/province/district tuple, and retain optimistic concurrency. Unknown historical details remain null.
- Inbound receipts accept `productionDate` and `receivedAt`, preserve them in lot reads, reject invalid chronology and validate quantity units against the SKU. Omitted dates preserve legacy request compatibility; they do not establish historical production facts. New transfer destination lots retain the source production date.
- Driver coordinate capture requires an active workday with location enabled and an assigned `DISPATCHED` or `IN_TRANSIT` delivery. Buyer reads require their own active delivery. Closing a delivery prevents further capture for that delivery and removes its Buyer tracking access. Starting a workday with location disabled is supported. Existing coordinate retention policy is unchanged.

Central migrations V164–V167 and Tenant migrations V24–V25 are additive. Tenant readiness now requires V24 and V25. Published migrations remain unchanged.

`node ops/scripts/seed-functional-demo.mjs --apply` enriches configured synthetic local profiles and creates labeled warehouse and Buyer addresses through authenticated local APIs. It requires the local profile and `.test`/`.local` identities and does not mutate the catalog. Optional `--simulate-gps` requires an existing assigned active delivery; it cannot manufacture a completed delivery.

Technical checks executed for this increment:

- `./mvnw -Dnexa.integration.enabled=true '-Dtest=CatalogManagementIT#newlyCreatedSkuCanBeReadByBuyerOnlyAfterExplicitActivation,WarehouseStructuredAddressIT,WarehouseInventoryPagingIT#receivingPreservesProductionAndEntryDatesAndRejectsInvalidChronology,DriverLiveLocationIT' test`: four passed, no skips.
- `./mvnw -Dnexa.integration.enabled=true '-Dtest=DriverTrackingIT,LocalTenantBusinessDatabaseProvisioningCliTests' test`: fourteen passed, no skips, after adding an assigned active-delivery fixture to the retained central tracking test.
- Local Docker API build: 865 discovered tests, zero failures, 319 integration tests skipped by their opt-in gate. The separate enabled integration commands above provide the applicable PostgreSQL evidence.
- Local Tenant provisioning applied V24 and V25 and verified identity, Workspace anchor and writer restrictions. Recreating the API container requires the existing `ops/scripts/reconcile-local-tenant-api-networks.sh` recovery step before Tenant workflows; central readiness alone does not verify those private connections.

These checks do not establish complete US/AC coverage, physical GPS accuracy, device permission behavior, Product Acceptance or production readiness. A live map provider must be explicitly configured; map-provider approval and physical-device checks remain separate gates.

### Document download regression found during local integration

The connected Buyer test found a generated document whose stored PDF is absent. This is unresolved stored-content evidence; its cause and recovery are not established. Metadata alone is not proof that bytes remain available.

The download controller now opens content before returning binary headers and uses Spring `InputStreamResource` with the resource message converter. Typed storage failures can therefore produce the existing sanitized `502 STORAGE_UNAVAILABLE` problem before committing a download response. The stream remains bounded in memory and is closed by the converter. Both document and evidence downloads share this path. This follows the official [Spring resource API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/io/InputStreamResource.html) and [resource converter API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/converter/ResourceHttpMessageConverter.html).

`./mvnw '-Dtest=BusinessDocumentDownloadTests,BusinessDocumentStorageSecurityTests,BusinessDocumentArchitectureTests' test` passed eleven tests without skips, including binary-byte equality, stream closure, and a sanitized failure without download headers. This correction does not recover the missing historical PDF or establish a passing full Buyer workflow.

A separate new-generation failure was traced to the local named volume directory: it was owned by root with mode 0755 while the API runs as UID/GID 10001. The local directory ownership was corrected to 10001:10001 with mode 0700. The Dockerfile now prepares that private directory before switching users, so new volumes inherit the intended ownership. Local storage initialization also checks an actual temporary write/delete and fails at startup when storage cannot be written. It does not reset failed document jobs or rewrite issued-document history.

Compose also runs `modern-object-storage-init` before the API, repairing the existing named volume with directories 0700/files 0600 owned by UID/GID 10001. The initializer has no network, no credentials, a read-only root filesystem and access only to that private volume. It rejects symlinks and special entries before changing ownership. It ran successfully against the existing local volume; isolated temporary-filesystem checks also passed for an empty root-owned directory and rejection of a symlink. The subsequent `docker compose --env-file .env.local -f ops/compose/modern.compose.yml up -d modern-api` recreation completed the initializer successfully before starting the API. Readiness returned `UP`, and an API-container write/delete probe succeeded as UID 10001. Docker's [volume initialization behavior](https://docs.docker.com/engine/storage/volumes/#mounting-a-volume-over-existing-data) explains why image-layer ownership alone cannot repair a pre-existing volume.

The same focused command passed thirteen tests after adding startup directory/probe checks. The enabled PostgreSQL document regression command (`BusinessDocumentGenerationAuthorizationIT,BusinessDocumentReplacementIT,BusinessDocumentTenantIsolationIT`) passed nine tests without skips after fixing the replacement test to account for the worker's bounded one-request queue processing. The first run failed because an earlier queued order document preceded the replacement; the worker limit and generation assertions remain intact.

### Transfer merge chronology

Transfer receipt now compares both expiration and production dates before merging into an existing destination lot with the same SKU and batch. Different dates, including a known production date versus an unknown historical value, reject the merge without inferring a date or changing destination stock. The transfer remains in transit, preserving the existing dispatched stock history; resolving incompatible lot metadata requires an authorized operational correction.

`./mvnw -Dnexa.integration.enabled=true '-Dtest=WarehouseSafetyStockTransferIT,BusinessDocumentStorageSecurityTests,BusinessDocumentDownloadTests,BusinessDocumentArchitectureTests' test` passed 21 tests without skips. Transfer coverage includes matching chronology, conflicting expiration, conflicting production and unknown destination production, alongside the four existing transfer/safety-stock tests.

### Rebuilt local runtime and synthetic tracking

The subsequent `docker compose --env-file .env.local -f ops/compose/modern.compose.yml build modern-api` completed with 871 discovered tests, zero failures and 320 skipped tests; 551 tests executed successfully. Enabled integration results above remain separate evidence. Central migration V168 was present and successful after recreation; private Tenant networks were reconciled before connected workflows.

`node ops/scripts/seed-functional-demo.mjs --apply --simulate-gps` completed against the recreated local API. Six synthetic coordinates were accepted through Driver endpoints and each matching sample was read through the related Buyer's active-delivery endpoint. Profiles and labeled addresses use authenticated API writes; the catalog was unchanged. This verifies connected local API tracking with synthetic data, not physical-device GPS or map rendering.

### Buyer receipt evidence and connected clients

Buyer evidence uses the existing immutable disputed receipt fact as subject `BUYER_RECEIPT`, not a new discrepancy aggregate. Central V168 grants the narrow `buyer.receipt.evidence.attach` permission. Upload, list and download revalidate Tenant/Workspace, Delivery/Attempt linkage and the current Buyer relationship; generic evidence listing excludes receipt evidence unless its subject is explicitly requested. Receipt evidence cannot be deleted through the generic deletion endpoint. Existing content validation and scanning remain authoritative.

`./mvnw -Dnexa.integration.enabled=true -Dtest=MobileV1CoreContractsIT test` passed all twelve tests with zero failures, errors or skips after the revoked-relationship access fence correction. These integration tests did not exercise a connected upload/scanner workflow; persistent-runtime evidence is recorded below.

The Web command `npx playwright test e2e/portal/real-buyer-order-flow.spec.mjs --project=portal-chromium` passed one test in 47.9 seconds against the rebuilt runtime. It covered Buyer request submission, Sales conversion/confirmation, Warehouse picking/packing/staging, Logistics assignment and dispatch, Buyer foreground refresh to `DISPATCHED`, document generation and a successful PDF download.

`node ops/scripts/seed-functional-demo.mjs --apply --receive-stock` created one labeled synthetic inbound lot in the synthetic Warehouse and zone through the real local API, using an existing catalog SKU and its authoritative unit and temperature limits. The same-key POST replay returned the same lot with quantity five and preserved production, entry and expiration dates; a subsequent lot GET confirmed persisted quantity. Running the command again reused the existing lot without receiving additional stock. The catalog was unchanged. This is persistent API evidence, not a Web or Android form submission.

From the Mobile repository, `python3 scripts/run-buyer-local-api-tests.py` with the private fixture supplied through `NEXA_BUYER_LOCAL_API_ENV_FILE` passed five connected Flutter tests without skips. These exercised scoped reads, brand/category references, purchase-request submission/detail and protected PDF bytes/checksum. Empty receivables/payment history fixtures supplied read-path evidence only. This is a native HTTP client integration run, not emulator or physical-device UI acceptance. The earlier missing historical PDF remains unresolved; the successful download used an available document.

### Assigned Driver POD evidence recovery

The local delivery exercise on 2026-10-15 verified a synthetic partial attempt, Buyer handoff, disputed receipt same-key replay, and final `DELIVERED` state. Buyer live-location returned `404` after terminal delivery and the Driver created its POD. The first real `PROOF_OF_DELIVERY` multipart upload returned HTTP `400 INVALID_REQUEST`; a separate upload to the existing disputed Buyer receipt returned `201`, reached `AVAILABLE`, and was identified as `image/png` by the scanner. No additional delivery or receipt was created.

The failure was in BC-09 subject resolution: its document source intentionally exposes PODs only after `SEALED` or `COMPLETED`, while Driver photo evidence must first be uploaded against a `PENDING` POD. Multipart handling, Native-client authentication and scanner processing worked on the Buyer receipt. Evidence upload now has a separate POD lookup limited to `PENDING`/`CAPTURED`, scoped to Tenant and Workspace and the currently assigned Driver membership/user. The existing finalized lookup remains the only lookup used for issued POD documents, so a `PENDING` POD cannot enqueue a `POD_REPORT`. Finalized-POD evidence retains its previous lookup path.

`./mvnw -Dnexa.integration.enabled=true '-Dtest=MobileV1CoreContractsIT#driverProofIsBoundToOwnedFinalAttemptAndSubjectScopedEvidence' test` passed one PostgreSQL-backed integration test without skips. It verifies that a pending POD is visible only through the assigned-Driver evidence lookup, rejects a draft `POD_REPORT` without creating a generation request or document, rejects upload by another Logistics membership, uploads the Driver photo through the multipart API to `AVAILABLE`, and preserves attach/replay/seal behavior. `node --check ops/scripts/seed-functional-demo.mjs` passed for the explicit `--resume-evidence UUID` recovery mode. That mode checks the same existing terminal delivery in Driver and Buyer scopes, requires its existing disputed receipt and scanned attachment, resolves the unique delivered attempt through a scoped read, reuses an existing POD photo or a stable upload/attach idempotency key, and does not refresh grants or create a receipt/delivery.

`node ops/scripts/seed-functional-demo.mjs --apply --resume-evidence 56b17cf2-672d-42e7-8664-96f101753a66` passed twice against the rebuilt local API. It reused the existing scanned `image/png` Driver photo, attached it to the existing POD, and confirmed the photo through a scoped read; both runs preserved the existing disputed Buyer receipt and created no delivery or receipt. The initial post-rebuild recovery attempt had uploaded the photo but could not attach it because the POD projection omits `attemptId`; the recovery script now resolves the unique delivered attempt through the scoped Fulfillment delivery read. Focused Warehouse ACL checks passed 14/14 and 16/16 tests. The local Docker API build discovered 873 tests, skipped 321 opt-in integration tests, and executed 552 with zero failures. These checks do not establish Product Acceptance or production readiness.
