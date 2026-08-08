# Evidence index

Artefactos binarios y trazas grandes se guardan fuera de Git bajo:

`../../../../../output/playwright/nexa-v1-definitive-closure/`

Este índice registra solo nombres, correlación, comandos y checksums necesarios. No se versionan cookies, tokens, passwords, uploads sensibles ni traces con secretos.

## Subtask 0

- `audit-current/01-portal-workspace-not-recognized.png`
- `audit-current/02-platform-forbidden.png`
- `audit-current/03-legacy-platform-contact-sheet.png`
- `audit-current/04-legacy-portal-contact-sheet.png`
- Captura visual del Payment Element montado en el browser final: evidencia emitida en la sesión de auditoría; no se versionan capturas efímeras del proveedor externo.

## Documentos y adapters

- XML UBL validado desde MinIO con `mc cat ... | xmllint --noout -`; la evidencia de checksum/objeto se conserva en el runtime ledger.
- Stripe SDK oficial ejercitado contra WireMock local; ClamAV TCP real validó archivo limpio y rechazó EICAR.
