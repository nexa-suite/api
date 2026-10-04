# API operations

The current local API runtime is the modern Compose stack in
[`compose/modern.compose.yml`](./compose/modern.compose.yml). Prepare the local
environment with `scripts/setup-local-environment.sh`, then use
`compose/scripts/modern-up.sh`, `modern-down.sh` and `status.sh`.

Legacy Compose files and scripts remain as comparison and compatibility
evidence. They are not the default runtime. `compare-up.sh` intentionally starts
both stacks; use `modern-up.sh` for the current API runtime.

See [Compose details](./compose/README.md) and the
[script inventory](../scripts/README.md). This navigation does not change
service topology or runtime behavior.

## Academic Render and Neon deployment

The Render service uses GitHub `main`, the Free plan and the existing Neon
`production` branch. Render receives the public database and Object Storage
identifiers from [`render.yaml`](../render.yaml); passwords and access keys
remain service secrets and are never stored in the repository.

The academic deployment sets `NEXA_CLAMAV_MODE=disabled` because no private
ClamAV service is available on the Free plan. This mode is fail-closed:
evidence that requires malware scanning is rejected with
`MALWARE_SCANNER_DISABLED`, remains unavailable and cannot be downloaded.
Generated business documents that do not require malware scanning continue to
use their existing flow. Network scanning remains the default outside this
explicit deployment configuration, and deterministic local scanning remains
restricted to local or test profiles.
