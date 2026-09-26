# Developer scripts

Script paths stay at their current locations because workflow and developer
commands reference them directly.

## Environment setup

- `setup-local-environment.sh` prepares local runtime configuration.
- `generate-local-env.sh` creates local environment values.
- `generate-local-signing-keys.sh` creates local signing keys.

## Local identities and access

- `reset-local-demo-identities.sh` resets local demo identities.
- `show-local-access.sh` displays local access details.
- `verify-local-access.sh`, `verify-local-demo-logins.sh` and
  `verify-local-workspace.sh` check local access setup.

## Security and evidence

- `verify-local-security.sh` runs local security checks.
- `create-review-snapshot.sh` creates a review evidence snapshot.

Use only the commands needed for the task. Reset scripts change local demo
data; they are not part of routine verification.
