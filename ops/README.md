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
