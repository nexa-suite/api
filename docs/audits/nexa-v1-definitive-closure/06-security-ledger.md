# Security ledger

Target: ASVS 5 Level 2 según matriz activa. Baseline, no certificación.

| Control | Evidencia source/runtime | Estado baseline |
|---|---|---|
| Access JWT corto + refresh opaco | IAM domain/application + cookie controller | PASS en suite e integración |
| Refresh rotation/reuse rejection | session service/tests | PASS en integración obligatoria |
| Cookie HttpOnly/SameSite/path | AuthenticationController | PASS en tests; browser autenticado ejecutado |
| CORS narrow | allowlist exacta de cuatro orígenes locales | PASS; origen externo 403 |
| Origin guard | `CookieOriginGuardFilter` | PASS en integración; no se debilitó |
| CSRF posture | Bearer + guarded cookie mutations | PASS en suite de seguridad |
| BOLA/BFLA | access context, permissions, scoped queries | PASS en integración/RLS |
| Authorization version | membership state + JWT/session checks | PASS en integración y flujo browser |
| RLS | forced policies en tablas principales | PASS en integración y runtime |
| CSP | frontend + API headers | PASS en builds/headers; ZAP baseline remoto sin fallos |
| Rate limiting | auth/preview/system operator tables | PASS en suite e integración |
| File security | MIME, checksum, ClamAV, private storage | checksum/MinIO PASS; ClamAV TCP real PASS con limpio/EICAR; HTTP cross-tenant read/download PASS (404, sin exposición) |
| Stripe | signature/dedup/service amount | SDK oficial + WireMock PASS; firma/dedup/importe/settlement/receipt PASS |
| Secret handling | ignored `.env.local`; no valores registrados | Trivy/SBOM remoto PASS (0 vulnerabilidades, 0 secretos y 0 misconfiguraciones); no se declara certificación |

## Riesgos P0/P1 baseline

1. El host mismatch histórico quedó corregido usando el host canónico local y la allowlist exacta; no se habilitó wildcard ni Origin bypass.
2. La integración final no marcó skips; los warnings de Spring/Mockito son no bloqueantes.
3. El grant de V64 y la normalización FEFO quedaron cubiertos por migración/test.
4. MinIO fue ejercitado con documentos generados y checksums coincidentes; ClamAV real rechazó EICAR y el adapter Stripe oficial fue ejercitado contra WireMock.
5. La matriz local autenticada y la ejecución remota de OWASP ZAP API baseline pasaron; ZAP reportó `FAIL-NEW: 0`, `FAIL-INPROG: 0`, `WARN-NEW: 7`, `PASS: 60` sobre 13 URLs. Trivy/SBOM remoto no detectó vulnerabilidades, secretos ni misconfiguraciones. Esto es evidencia baseline, no certificación ASVS ni DAST autenticado por rol.
