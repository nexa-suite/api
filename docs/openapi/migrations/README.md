# v2.0.0 contract migration gate

Status: **Owner-reviewed migration; gate configured in CI**. The Product Owner
approved the exact candidate and findings on 2026-11-02 at 17:54:03 UTC;
[PR #133](https://github.com/nexa-suite/api/pull/133) records the decision.
This decision is not a GitHub pull request review or an approval of release.

The [v2.0.0 manifest](./v2.0.0.json) records the complete findings returned by
the existing compatibility checker for the committed runtime snapshot at API
commit `31943493530edd5df07817d00c9b1874e86dbf5f`. It records the reviewed migration, not a claim of backward compatibility. The candidate's raw
JSON SHA256 is
`a614f7698ad13da6acd172e97df2eab7c0247ba51c421ea4e957f4b8ce268861`.

| Comparison ref | Resolved baseline commit | Findings |
|---|---|---:|
| `689be612d96079feb02a155fbe76d829a10bdeb1` (PR main base) | `689be612d96079feb02a155fbe76d829a10bdeb1` | 429 |
| `acbb76f56deba09fcb0ee31ea6854238dcb7486b` (develop back-merge base) | `acbb76f56deba09fcb0ee31ea6854238dcb7486b` | 429 |
| `v0.17.1` | `be03488df7b3442ad0016af822667817c3fcddab` | 314 |
| `v0.18.0` | `05cb9ed3100e44d7ab0c6593cf6fbda86a4aa383` | 328 |
| `v0.19.0` | `89ff511c30976cbb1734ab16ecd3fadac0651ed4` | 328 |
| `v0.20.0` | `017b7a8e80ba7a11710d9707b43ac2b26696a5f0` | 415 |
| `v0.21.0` | `ad7e46a07fa2e2f5a5baced860812bf4d96080d9` | 415 |

Each manifest entry also contains the exact baseline JSON SHA256 and the full
sorted findings list, including repeated findings. Counts are comparison-local;
they must not be added together as distinct consumer changes. The PR main base
uses its immutable commit rather than a moving local `origin/main` ref. The
manifest binds the checker source SHA256 as well as the two contract snapshots.

## Additional back-merge baseline

The Owner approved adding the exact develop baseline
`acbb76f56deba09fcb0ee31ea6854238dcb7486b`; the decision was recorded at
2026-11-03T12:49:15+00:00 in [PR #139](https://github.com/nexa-suite/api/pull/139).
The manifest timestamp records this additional decision, not a GitHub review
event or an inferred earlier decision time. The original six-baseline approval
remains recorded in PR #133.

The added baseline raw OpenAPI SHA256 is
`2adc09eaed575b1f77b0d035625308dab45ec7c82611067b49aabba82adb393f`.
Compared with the existing immutable main baseline, its document differs only
in `info.version` (`1.2.0` instead of `2.0.0`) and produces the same exact 429
findings against the unchanged candidate snapshot. This additional record does
not change the checker, candidate bytes, existing baseline records, or published
v2.0.0 tag. It grants no exemption to another ref, hash or finding list.

## Gate behavior

`.github/scripts/check-openapi-migration.py` always runs the existing
`check_compatibility` function first. A compatible comparison passes through the
normal checker, including a compatible future candidate against its own base.
It does not consume or carry forward a historical migration allowance.

If the checker reports a break, this helper fails unless all of these match:

- The manifest has a supported strict structure and no duplicate JSON keys.
- The checker, candidate version and candidate raw-byte SHA256 match the record.
- The supplied base ref is explicitly listed, resolves to its recorded commit,
  and has the recorded raw-byte SHA256.
- The complete sorted findings list matches exactly; no finding is added,
  removed or silently generalized.
- Approval status is `reviewed`, with a named Owner, an HTTPS decision-evidence
  URL and a timestamp with timezone.

The manifest records status `reviewed` and the Product Owner decision evidence.
Missing or malformed manifests, unknown breaking refs or versions, snapshot
changes and additional breaks fail closed. Even a compatible metadata edit to a
candidate that still contains the recorded breaks changes its hash and requires
renewed evidence and review.

The helper does not validate that a linked decision is authentic. Major version
`2.0.0` does not itself permit any finding. There are no header, method, security
or version-wide exemptions. The original checker and its existing four-field
Buyer privacy exception remain unchanged. CI retains all six comparisons and
security and runtime checks; the original checker still reports these breaks.

## Review and validation

Run from the repository root:

```bash
python3 .github/scripts/test_check_openapi_compatibility.py
python3 .github/scripts/test_check_openapi_migration.py
python3 .github/scripts/check-openapi-migration.py \
  --base-ref 689be612d96079feb02a155fbe76d829a10bdeb1
```

The first two commands validate the existing checker and the helper's failure
and exact-match behavior. Reviewed decisions in synthetic fixtures are test data
only. The third command passes only for the exact reviewed candidate and findings.
Use the tag names above as `--base-ref` for the other comparisons. CI resolves
the pull request base to its immutable commit before comparison. A different
base with breaking findings fails unless separately reviewed and recorded; a
compatible comparison passes normally.

Owner acceptance includes consumer migration and generated SDK regeneration.
It does not prove consumers have migrated or runtime security is correct. This
gate does not change release, tag-signing, deployment or acceptance requirements.

## Historical contract corrections traced against main

The comparison against main contains 429 findings, not 429 demonstrated runtime
regressions. Static source tracing against
`689be612d96079feb02a155fbe76d829a10bdeb1` established these families:

- 153 `If-Match` and 95 `Idempotency-Key` requirements already rejected missing
  values in the baseline handlers or their application/persistence validators.
- All 58 removed `200` responses were already `201`, `202` or `204` on the wire.
- 15 security declarations describe existing, unchanged security filter chains.
- 104 schema findings result from generic-name collisions, explicit Buyer schema
  names and removal of framework models accidentally exposed as query data.
  Buyer draft model blobs are unchanged; generated model names can still change.
- Three unused `headers` query parameters disappear, and the Buyer self-account
  `404` now documents the existing Problem Details response instead of a success
  DTO. The four approved private Buyer-field removals retain their separate,
  existing exact exception.

The three draft-resume routes preserve both accepted token headers. Neither
individual header is unconditionally required; one must be supplied, and a
nonblank `X-Resume-Token` takes precedence. Runtime missing/altered-token rejection
and the compatibility alias were checked with PostgreSQL integration tests.

These findings require exact migration review rather than a version-wide
allowance. Consumers generated from historical snapshots must regenerate models,
send the already-required version/idempotency headers, and handle actual success
and Problem Details responses. This evidence does not claim complete runtime
compatibility or connected-client acceptance.

## Compatible patch v2.0.1

The Product Owner approved the exact candidate SHA256
`de215cb0a335b717951fc3fb424bdf2e08d926643ecbbdfc7a085bb955bb13a3`
in the execution conversation; [PR #139](https://github.com/nexa-suite/api/pull/139) records that decision separately from GitHub review.
The document differs from published v2.0.0 only in `info.version`; their strict comparison has zero findings.
The [v2.0.1 manifest](v2.0.1.json) explicitly records all seven historical baselines.
Its findings also enumerate the four private Buyer self-account fields already removed in v2.0.0, because the historical checker exception is tied to version 2.0.0.
The strict compatibility checker and its digest remain unchanged. Any extra finding or different candidate hash fails.
