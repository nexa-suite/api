#!/usr/bin/env node
/** Adds named synthetic fixtures through the real local API; never imports catalog data. */
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8080/api/v1';
if (!process.argv.includes('--apply')) {
  console.error('Usage: node ops/scripts/seed-functional-demo.mjs --apply [--simulate-gps] [--receive-stock] [--exercise-delivery UUID] [--resume-evidence UUID]');
  process.exit(2);
}
function stop(message) { throw new Error(message); }
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const exerciseIndex = process.argv.indexOf('--exercise-delivery');
const exerciseDeliveryId = exerciseIndex < 0 ? null : process.argv[exerciseIndex + 1];
if (exerciseIndex >= 0 && !uuidPattern.test(exerciseDeliveryId ?? '')) {
  stop('Delivery exercise requires an explicit delivery UUID.');
}
const resumeIndex = process.argv.indexOf('--resume-evidence');
const resumeDeliveryId = resumeIndex < 0 ? null : process.argv[resumeIndex + 1];
if (resumeIndex >= 0 && (!uuidPattern.test(resumeDeliveryId ?? '') || exerciseIndex >= 0
  || process.argv.includes('--simulate-gps') || process.argv.includes('--receive-stock'))) {
  stop('Evidence recovery requires one explicit delivery UUID and cannot be combined with other fixture actions.');
}
const configuration = spawnSync('docker', ['compose', '--env-file', '.env.local', '-f', 'ops/compose/modern.compose.yml', 'config', '--format', 'json'], { cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
if (configuration.status !== 0) stop('Local Compose configuration is unavailable.');
const env = JSON.parse(configuration.stdout).services?.['modern-api']?.environment;
if (!env || !String(env.SPRING_PROFILES_ACTIVE).split(',').includes('local')) stop('Synthetic fixtures require the local API profile.');
const workspaceSlug = env.NEXA_DEV_WORKSPACE_SLUG;
if (!workspaceSlug) stop('The local workspace fixture is not configured.');

async function request(path, { token, method = 'GET', body, etag, key } = {}) {
  const response = await fetch(base + path, {
    method, redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json', 'X-Nexa-Client': 'NATIVE',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(etag ? { 'If-Match': etag } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) stop(`${method} ${path.split('?')[0]} returned HTTP ${response.status}.`);
  const text = await response.text();
  return { body: text ? JSON.parse(text) : null, etag: response.headers.get('ETag') };
}
async function signIn(kind, surface = 'PLATFORM') {
  const identifier = env[`NEXA_DEV_${kind}_EMAIL`];
  const password = env[`NEXA_DEV_${kind}_PASSWORD`];
  if (!identifier || !password) return null;
  if (!/@[^@]+\.(?:test|local)$/.test(identifier)) stop('Only synthetic .test/.local identities may be enriched.');
  const result = await request('/authentication/sign-in', { method: 'POST', body: { identifier, password, workspaceSlug, surface } });
  return result.body.accessToken;
}

const syntheticPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg==', 'base64');
async function problemSummary(response) {
  let problem = {};
  try { problem = await response.json(); } catch { /* keep the HTTP status only */ }
  const safePart = (value) => typeof value === 'string' ? value.replace(/[\r\n\t]+/g, ' ').slice(0, 180) : '';
  const code = safePart(problem.code);
  const detail = safePart(problem.detail);
  return `HTTP ${response.status}${code ? ` ${code}` : ''}${detail ? `: ${detail}` : ''}`;
}
async function uploadEvidence(token, subjectType, subjectId, { key = crypto.randomUUID(), filename = 'synthetic-evidence.png' } = {}) {
  const form = new FormData();
  form.set('subjectType', subjectType);
  form.set('subjectId', subjectId);
  form.set('file', new Blob([syntheticPng], { type: 'image/png' }), filename);
  const response = await fetch(base + '/business-document-evidence', {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(30000), body: form,
    headers: { Authorization: `Bearer ${token}`, 'X-Nexa-Client': 'NATIVE', 'Idempotency-Key': key },
  });
  if (!response.ok) stop(`Synthetic ${subjectType} upload failed: ${await problemSummary(response)}.`);
  let evidence = await response.json();
  for (let poll = 0; evidence.lifecycleStatus !== 'AVAILABLE' && poll < 30; poll++) {
    if (['REJECTED', 'DELETED', 'QUARANTINED'].includes(evidence.lifecycleStatus)) stop('Synthetic evidence was rejected; scanner controls were preserved.');
    await new Promise((resolve) => setTimeout(resolve, 1000));
    evidence = (await request(`/business-document-evidence/${evidence.id}`, { token })).body;
  }
  if (evidence.lifecycleStatus !== 'AVAILABLE' || evidence.subjectType !== subjectType || evidence.subjectId !== subjectId
    || evidence.detectedContentType !== 'image/png') stop('Synthetic evidence did not reach the expected scanned subject and MIME state.');
  return evidence;
}

if (resumeDeliveryId) {
  const driver = await signIn('LOGISTICS');
  const buyer = await signIn('BUYER', 'PORTAL');
  if (!driver || !buyer) stop('Configured local Driver and Buyer identities are required for evidence recovery.');

  const deliveryPath = `/driver/deliveries/${resumeDeliveryId}`;
  const delivery = (await request(deliveryPath, { token: driver })).body;
  const buyerDelivery = (await request(`/buyer/deliveries/${resumeDeliveryId}`, { token: buyer })).body;
  if (delivery.id !== resumeDeliveryId || delivery.status !== 'DELIVERED'
    || buyerDelivery.id !== resumeDeliveryId || buyerDelivery.status !== 'DELIVERED') {
    stop('Evidence recovery requires the same terminal DELIVERED fact in assigned Driver and related Buyer scopes.');
  }
  const fulfillmentDelivery = (await request(`/deliveries/${resumeDeliveryId}`, { token: driver })).body;
  const deliveredAttempts = (fulfillmentDelivery.attempts ?? []).filter((attempt) => attempt.outcome === 'DELIVERED');
  if (fulfillmentDelivery.id !== resumeDeliveryId || fulfillmentDelivery.status !== 'DELIVERED'
    || deliveredAttempts.length !== 1 || !deliveredAttempts[0].id) {
    stop('Evidence recovery requires one unambiguous delivered attempt in the scoped Fulfillment read.');
  }
  const attemptId = deliveredAttempts[0].id;

  const podResult = await request(`/deliveries/${resumeDeliveryId}/pod`, { token: driver });
  const pod = podResult.body;
  if (!pod?.id || pod.deliveryId && pod.deliveryId !== resumeDeliveryId
    || !['PENDING', 'CAPTURED', 'SEALED'].includes(pod.status)
    || pod.status === 'SEALED' && !pod.photoEvidenceObjectId) {
    stop('Evidence recovery requires the existing attachable POD for this terminal delivery.');
  }
  const receipts = (await request(`/buyer/deliveries/${resumeDeliveryId}/buyer-receipts`, { token: buyer })).body;
  const disputed = (Array.isArray(receipts) ? receipts : []).filter((item) => item.decision === 'DISPUTED');
  if (disputed.length !== 1) stop('Evidence recovery requires one unambiguous existing disputed Buyer receipt.');
  const receipt = disputed[0];
  const receiptEvidence = (await request(`/business-document-evidence?subjectType=BUYER_RECEIPT&subjectId=${receipt.id}`, { token: buyer })).body;
  const availableReceiptEvidence = receiptEvidence.items?.filter((item) => item.lifecycleStatus === 'AVAILABLE') ?? [];
  if (availableReceiptEvidence.length === 0) stop('The existing disputed receipt has no scanned attachment; no replacement receipt or attachment was created.');
  for (const item of availableReceiptEvidence) {
    const evidence = (await request(`/business-document-evidence/${item.id}`, { token: buyer })).body;
    if (evidence.subjectType !== 'BUYER_RECEIPT' || evidence.subjectId !== receipt.id || evidence.lifecycleStatus !== 'AVAILABLE') {
      stop('Existing Buyer receipt evidence did not retain its scanned subject binding.');
    }
  }

  const stableFilename = `synthetic-driver-pod-photo-${resumeDeliveryId}.png`;
  const podEvidence = (await request(`/business-document-evidence?subjectType=PROOF_OF_DELIVERY&subjectId=${pod.id}`, { token: driver })).body;
  let photoId = pod.photoEvidenceObjectId;
  let photo;
  if (photoId) {
    photo = (await request(`/business-document-evidence/${photoId}`, { token: driver })).body;
    if (photo.subjectType !== 'PROOF_OF_DELIVERY' || photo.subjectId !== pod.id || photo.lifecycleStatus !== 'AVAILABLE') {
      stop('Existing POD photo is not an available scanned object bound to this POD.');
    }
  } else {
    if (pod.status === 'SEALED') stop('Sealed POD has no photo to recover; the existing fact was left unchanged.');
    const priorPhoto = podEvidence.items?.filter((item) => item.originalFilename === stableFilename) ?? [];
    if (priorPhoto.length > 1) stop('Multiple prior POD uploads match the recovery filename; inspect the evidence before retrying.');
    if (priorPhoto.length === 1) {
      photo = (await request(`/business-document-evidence/${priorPhoto[0].id}`, { token: driver })).body;
      if (photo.lifecycleStatus !== 'AVAILABLE' || photo.subjectType !== 'PROOF_OF_DELIVERY' || photo.subjectId !== pod.id) {
        stop('Prior POD upload is not available for attachment; scanner controls were preserved.');
      }
    } else {
      photo = await uploadEvidence(driver, 'PROOF_OF_DELIVERY', pod.id, {
        key: `synthetic-driver-pod-photo-${resumeDeliveryId}`, filename: stableFilename,
      });
    }
    photoId = photo.id;
  }

  if (!pod.photoEvidenceObjectId) {
    const currentPodResult = await request(`/deliveries/${resumeDeliveryId}/pod`, { token: driver });
    const currentPod = currentPodResult.body;
    if (currentPod.id !== pod.id || !['PENDING', 'CAPTURED', 'SEALED'].includes(currentPod.status)
      || currentPod.photoEvidenceObjectId && currentPod.photoEvidenceObjectId !== photoId
      || currentPod.status === 'SEALED' && currentPod.photoEvidenceObjectId !== photoId) {
      stop('POD changed during evidence recovery; inspect its current photo before retrying.');
    }
    if (!currentPod.photoEvidenceObjectId) {
      const attached = await request(`${deliveryPath}/attempts/${attemptId}/proof-of-delivery/${currentPod.id}/evidence`, {
        token: driver, method: 'POST', key: `synthetic-driver-pod-attach-${resumeDeliveryId}`,
        etag: currentPodResult.etag, body: { kind: 'PHOTO', evidenceObjectId: photoId },
      });
      if (attached.body.photoEvidenceObjectId !== photoId) stop('Driver POD did not retain the recovered scanned photo.');
    }
  }

  const verifiedPod = (await request(`/deliveries/${resumeDeliveryId}/pod`, { token: driver })).body;
  if (verifiedPod.id !== pod.id || verifiedPod.photoEvidenceObjectId !== photoId
    || !['CAPTURED', 'SEALED'].includes(verifiedPod.status)) stop('POD evidence attachment was not confirmed by a scoped read.');
  const verifiedReceiptHistory = (await request(`/buyer/deliveries/${resumeDeliveryId}/buyer-receipts`, { token: buyer })).body;
  if (!(Array.isArray(verifiedReceiptHistory) ? verifiedReceiptHistory : []).some((item) => item.id === receipt.id && item.decision === 'DISPUTED')) {
    stop('Buyer receipt history did not preserve the existing dispute.');
  }
  console.log(`Existing disputed Buyer receipt and scanned attachment verified; existing POD photo ${photoId} attached and confirmed. No delivery or receipt was created.`);
  process.exit(0);
}

const tokens = {};
for (const [index, kind] of ['OWNER', 'TENANT_ADMIN', 'SALES', 'WAREHOUSE', 'LOGISTICS', 'BOM', 'BUYER'].entries()) {
  const token = await signIn(kind, kind === 'BUYER' ? 'PORTAL' : 'PLATFORM');
  if (!token) continue;
  tokens[kind] = token;
  const current = await request('/me/profile', { token });
  const profile = current.body;
  // Preserve existing explicit profile values; these names are labeled test data.
  if (!profile.firstName || !profile.lastName) {
    await request('/me/profile', { token, method: 'PATCH', etag: current.etag, body: {
      displayName: profile.displayName, phone: profile.phone,
      preferredLanguage: profile.preferredLanguage, timezone: profile.timezone,
      firstName: profile.firstName || 'Prueba', lastName: profile.lastName || kind,
      ...(!profile.documentType && !profile.documentNumber && !profile.documentCountryCode ? {
        documentType: 'DNI', documentNumber: String(index + 1).padStart(8, '0'), documentCountryCode: 'PE',
      } : {}),
    } });
  }
}
if (!tokens.OWNER || !tokens.WAREHOUSE || !tokens.BUYER) stop('Owner, Warehouse and Buyer local fixtures are required.');

const warehouses = await request('/warehouses?size=100', { token: tokens.WAREHOUSE });
let warehouse = warehouses.body.items.find((item) => item.code === 'WH-DEMO-E2E');
if (!warehouse) warehouse = (await request('/warehouses', { token: tokens.WAREHOUSE, method: 'POST', body: {
  code: 'WH-DEMO-E2E', name: 'Almacén sintético de pruebas', address: 'Calle de Pruebas 100, Lima',
} })).body;
const actor = await request('/session', { token: tokens.WAREHOUSE });
await request(`/warehouses/${warehouse.id}/access-grants`, { token: tokens.OWNER, method: 'POST', body: { membershipId: actor.body.membership.membershipId } });
tokens.WAREHOUSE = await signIn('WAREHOUSE');
const location = await request(`/warehouses/${warehouse.id}/location`, { token: tokens.WAREHOUSE });
await request(`/warehouses/${warehouse.id}/location`, { token: tokens.WAREHOUSE, method: 'PATCH', etag: location.etag, body: {
  latitude: -12.0464, longitude: -77.0428,
  addressDetails: { countryCode: 'PE', departmentCode: '15', provinceCode: '1501', districtCode: '150101',
    city: 'Lima', roadType: 'STREET', streetName: 'Calle de Pruebas', streetNumber: '100', interior: 'B',
    postalCode: '15001', reference: 'Ubicación sintética para pruebas funcionales' },
} });

const account = await request('/client-accounts/me', { token: tokens.BUYER });
const addressPath = `/client-accounts/${account.body.id}/addresses`;
const addresses = await request(addressPath, { token: tokens.BUYER });
if (!addresses.body.some((item) => item.label === 'DEMO-GPS')) {
  await request(addressPath, { token: tokens.BUYER, method: 'POST', body: { label: 'DEMO-GPS', defaultAddress: false,
    address: { addressType: 'DELIVERY', line: 'Calle de Pruebas 200, Lima', reference: 'Dirección sintética de pruebas',
      countryCode: 'PE', departmentCode: '15', provinceCode: '1501', districtCode: '150101',
      recipientName: 'Contacto de pruebas', recipientPhone: '+51900000000', roadType: 'STREET',
      streetName: 'Calle de Pruebas', streetNumber: '200', interior: 'A', postalCode: '15001',
      receivingInstructions: 'Datos sintéticos; no realizar entregas físicas', receivingHours: '09:00-17:00',
      latitude: -12.0470, longitude: -77.0410, source: 'MANUAL' } } });
}

console.log('Synthetic profiles, warehouse address and Buyer delivery address are ready. Catalog unchanged.');
if (process.argv.includes('--receive-stock')) {
  const catalog = await request('/catalog-items?page=0&size=100', { token: tokens.WAREHOUSE });
  let selected;
  for (const item of catalog.body.items ?? []) {
    const detail = (await request(`/catalog-items/${encodeURIComponent(item.catalogItemId)}`, { token: tokens.WAREHOUSE })).body;
    if (detail.status === 'ACTIVE' && detail.sellableSkuId && detail.unitOfMeasure) {
      selected = detail;
      break;
    }
  }
  if (!selected) stop('No active catalog item with an authoritative SKU and unit exists in the first page.');
  const sku = (await request(`/skus/${selected.sellableSkuId}`, { token: tokens.OWNER })).body;
  const zones = (await request(`/warehouses/${warehouse.id}/zones?size=100`, { token: tokens.WAREHOUSE })).body.items;
  const zoneCode = 'DEMO-STOCK';
  let zone = zones.find((item) => item.code === zoneCode);
  if (!zone) zone = (await request(`/warehouses/${warehouse.id}/zones`, {
    token: tokens.WAREHOUSE, method: 'POST', body: {
      code: zoneCode, name: 'Zona sintética de recepción',
      type: sku.temperatureMax != null && Number(sku.temperatureMax) <= 0 ? 'FROZEN'
        : sku.temperatureMin != null || sku.temperatureMax != null ? 'CHILLED' : 'AMBIENT',
      temperatureMin: sku.temperatureMin, temperatureMax: sku.temperatureMax,
    },
  })).body;
  if (zone.status !== 'ACTIVE') stop('The synthetic receiving zone is inactive.');
  const batchNumber = 'DEMO-RECEIVING-001';
  const lotQuery = `/inventory/lots?warehouseId=${warehouse.id}&zoneId=${zone.id}&catalogItemId=${encodeURIComponent(selected.catalogItemId)}&size=100`;
  const prior = (await request(lotQuery, { token: tokens.WAREHOUSE })).body.items.find((item) => item.batchNumber === batchNumber);
  if (prior) {
    if (prior.skuId !== selected.sellableSkuId || !prior.productionDate || !prior.receivedAt || prior.unit !== selected.unitOfMeasure) {
      stop('The existing synthetic receipt does not match the expected SKU/date/unit evidence.');
    }
    console.log('Existing synthetic inbound lot verified; no additional stock received.');
  } else {
    const minimums = [sku.temperatureMin, zone.temperatureMin].filter((value) => value != null).map(Number);
    const maximums = [sku.temperatureMax, zone.temperatureMax].filter((value) => value != null).map(Number);
    const minimum = minimums.length ? Math.max(...minimums) : null;
    const maximum = maximums.length ? Math.min(...maximums) : null;
    if (minimum != null && maximum != null && minimum > maximum) stop('Synthetic zone and SKU temperature bounds do not intersect.');
    const temperatureReading = minimum != null && maximum != null ? (minimum + maximum) / 2 : minimum ?? maximum ?? 20;
    const receivedAt = new Date(Date.now() - 60000).toISOString();
    const productionDate = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
    const expirationDate = new Date(Date.now() + Math.max(30, sku.shelfLifeDays || 30) * 86400000).toISOString().slice(0, 10);
    const receipt = { warehouseId: warehouse.id, zoneId: zone.id, catalogItemId: selected.catalogItemId,
      skuId: selected.sellableSkuId, batchNumber, productionDate, expirationDate, receivedAt,
      quantity: 5, unit: selected.unitOfMeasure, temperatureReading,
      notes: 'Stock sintético para pruebas funcionales; no representa existencias físicas' };
    const key = crypto.randomUUID();
    const first = (await request('/inventory/inbound-receipts', { token: tokens.WAREHOUSE, method: 'POST', key, body: receipt })).body;
    const replay = (await request('/inventory/inbound-receipts', { token: tokens.WAREHOUSE, method: 'POST', key, body: receipt })).body;
    if (first.id !== replay.id || Number(replay.onHand) !== 5 || replay.productionDate !== productionDate
      || replay.expirationDate !== expirationDate || Date.parse(replay.receivedAt) !== Date.parse(receivedAt)
      || replay.unit !== selected.unitOfMeasure || replay.status !== 'AVAILABLE') {
      stop('Synthetic inbound receipt replay did not preserve stock, dates or unit.');
    }
    const persisted = (await request(`/inventory/lots/${first.id}`, { token: tokens.WAREHOUSE })).body;
    if (persisted.id !== first.id || Number(persisted.onHand) !== 5) stop('The synthetic receipt is not visible through lot reads.');
    console.log('Synthetic inbound receipt and same-key replay verified: quantity, SKU unit, temperature acceptance and production/entry/expiry dates. Catalog unchanged.');
  }
}
if (process.argv.includes('--simulate-gps')) {
  if (!tokens.LOGISTICS) stop('A configured local Driver identity is required.');
  const deliveries = await request('/driver/deliveries?page=0&size=100', { token: tokens.LOGISTICS });
  const buyerDeliveries = await request('/buyer/deliveries?page=0&size=100', { token: tokens.BUYER });
  const buyerDeliveryIds = new Set((buyerDeliveries.body.items ?? []).map((item) => item.id));
  const active = (Array.isArray(deliveries.body) ? deliveries.body : deliveries.body.items ?? []).find((item) =>
    ['DISPATCHED', 'IN_TRANSIT'].includes(item.status) && buyerDeliveryIds.has(item.id));
  if (!active) stop('No assigned active delivery visible to the configured Buyer exists in the first page. Create and dispatch a test order before simulating GPS.');
  let day = await request('/driver/workdays/current', { token: tokens.LOGISTICS });
  if (!day.body) day = await request('/driver/workdays', { token: tokens.LOGISTICS, method: 'POST', key: crypto.randomUUID(), body: { locationAvailable: false } });
  if (!day.body.locationAvailable) day = await request(`/driver/workdays/${day.body.id}/location-availability`, {
    token: tokens.LOGISTICS, method: 'POST', key: crypto.randomUUID(), etag: day.etag, body: { locationAvailable: true },
  });
  for (let index = 0; index < 6; index++) {
    const detail = await request(`/driver/deliveries/${active.id}`, { token: tokens.LOGISTICS });
    if (!['DISPATCHED', 'IN_TRANSIT'].includes(detail.body.status)) break;
    const sampleId = crypto.randomUUID();
    await request(`/driver/workdays/${day.body.id}/locations`, { token: tokens.LOGISTICS, method: 'POST', body: {
      sampleId, latitude: -12.0464 - index * 0.0001, longitude: -77.0428 + index * 0.0001,
      accuracyMeters: 5, capturedAt: new Date().toISOString(),
    } });
    const live = await request(`/buyer/deliveries/${active.id}/live-location`, { token: tokens.BUYER });
    if (live.body.deliveryId !== active.id || live.body.location?.sampleId !== sampleId) {
      stop('The configured Buyer did not receive the accepted synthetic GPS sample.');
    }
    console.log(`Synthetic GPS sample ${index + 1} accepted by Driver API and visible to the related Buyer.`);
    if (index < 5) await new Promise((resolve) => setTimeout(resolve, 2000));
  }
}

if (exerciseDeliveryId) {
  if (!tokens.LOGISTICS) stop('The local Driver identity is required.');
  const driver = tokens.LOGISTICS;
  const buyer = tokens.BUYER;
  const deliveryPath = `/driver/deliveries/${exerciseDeliveryId}`;
  let current = await request(deliveryPath, { token: driver });
  await request(`/buyer/deliveries/${exerciseDeliveryId}`, { token: buyer });
  if (current.body.status !== 'DISPATCHED' || current.body.activeAttempt) {
    stop('Delivery exercise requires an untouched assigned DISPATCHED delivery visible to this Buyer. Review any interrupted run before selecting another delivery.');
  }
  const initialLines = current.body.outcomeLines;
  if (!Array.isArray(initialLines) || initialLines.length === 0 || initialLines.some((line) =>
    !line.fulfillmentLineId || !line.skuId || !line.unit || !(Number(line.remainingQuantity) > 0))) {
    stop('Assigned delivery lines are unavailable for the synthetic exercise.');
  }
  // Exercise only exact integer quantities; do not silently round business decimals.
  if (initialLines.some((line) => !Number.isSafeInteger(Number(line.remainingQuantity)) || Number(line.remainingQuantity) < 2)) {
    stop('Delivery exercise requires each remaining quantity to be an integer of at least two units.');
  }
  await request(`/deliveries/${exerciseDeliveryId}/transit-starts`, {
    token: driver, method: 'POST', key: crypto.randomUUID(), etag: current.etag,
  });
  async function completeAttempt(outcome, partial) {
    current = await request(deliveryPath, { token: driver });
    const started = await request(`${deliveryPath}/attempts`, {
      token: driver, method: 'POST', key: crypto.randomUUID(), etag: current.etag,
    });
    const attemptId = started.body.attempt.id;
    const lines = current.body.outcomeLines.filter((line) => Number(line.remainingQuantity) > 0).map((line) => {
      const quantity = partial ? 1 : Number(line.remainingQuantity);
      if (!Number.isSafeInteger(quantity) || quantity <= 0) stop('Remaining quantities are not suitable for an exact synthetic attempt.');
      return { fulfillmentLineId: line.fulfillmentLineId, skuId: line.skuId,
        attemptedQuantity: quantity, deliveredQuantity: quantity, rejectedQuantity: 0,
        cancelledQuantity: 0, unit: line.unit };
    });
    const result = await request(`${deliveryPath}/attempts/${attemptId}/outcomes`, {
      token: driver, method: 'POST', key: crypto.randomUUID(), etag: started.etag,
      body: { outcome, lines, attemptedAt: new Date().toISOString(), notes: 'Resultado sintético de prueba; no representa entrega física' },
    });
    if (result.body.delivery.status !== outcome) stop('The server did not confirm the expected synthetic delivery outcome.');
    return { attemptId, etag: result.etag };
  }
  const partial = await completeAttempt('PARTIAL', true);
  const handoff = await request(`/deliveries/${exerciseDeliveryId}/handoff-tokens`, {
    token: driver, method: 'POST', key: crypto.randomUUID(), body: { attemptId: partial.attemptId },
  });
  if (!handoff.body.token) stop('A fresh Buyer handoff token was not issued.');
  const validation = await request('/delivery-handoff/validations', {
    token: buyer, method: 'POST', body: { token: handoff.body.token },
  });
  if (validation.body.deliveryId !== exerciseDeliveryId) stop('Buyer handoff did not resolve the selected delivery.');
  const receiptKey = crypto.randomUUID();
  const receiptBody = { token: handoff.body.token, decision: 'DISPUTED', acceptedQuantity: 0,
    reason: 'Discrepancia sintética para verificar adjuntos; no representa reclamo real' };
  const receipt = await request(`/deliveries/${exerciseDeliveryId}/buyer-receipts`, {
    token: buyer, method: 'POST', key: receiptKey, body: receiptBody,
  });
  const replay = await request(`/deliveries/${exerciseDeliveryId}/buyer-receipts`, {
    token: buyer, method: 'POST', key: receiptKey, body: receiptBody,
  });
  if (receipt.body.id !== replay.body.id || receipt.body.decision !== 'DISPUTED') stop('Buyer receipt replay changed its immutable identity.');
  console.log('Synthetic partial attempt, Buyer handoff and disputed receipt same-key replay verified.');

  const completed = await completeAttempt('DELIVERED', false);
  const closedTracking = await fetch(base + `/buyer/deliveries/${exerciseDeliveryId}/live-location`, {
    redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { Authorization: `Bearer ${buyer}`, 'X-Nexa-Client': 'NATIVE' },
  });
  if (closedTracking.status !== 404) stop('Completed delivery still exposes Buyer live tracking.');
  const pod = await request(`${deliveryPath}/attempts/${completed.attemptId}/proof-of-delivery`, {
    token: driver, method: 'POST', key: crypto.randomUUID(), etag: completed.etag,
    body: { receiverName: 'Receptor sintético de pruebas', capturedAt: new Date().toISOString(), notes: 'Prueba de entrega sintética; sin entrega física' },
  });
  const photo = await uploadEvidence(driver, 'PROOF_OF_DELIVERY', pod.body.id);
  const attached = await request(`${deliveryPath}/attempts/${completed.attemptId}/proof-of-delivery/${pod.body.id}/evidence`, {
    token: driver, method: 'POST', key: crypto.randomUUID(), etag: pod.etag,
    body: { kind: 'PHOTO', evidenceObjectId: photo.id },
  });
  if (attached.body.photoEvidenceObjectId !== photo.id) stop('Driver POD did not retain the scanned synthetic photo.');
  const buyerPhoto = await uploadEvidence(buyer, 'BUYER_RECEIPT', receipt.body.id);
  const records = (await request(`/buyer/deliveries/${exerciseDeliveryId}/buyer-receipts`, { token: buyer })).body;
  if (!records.some((item) => item.id === receipt.body.id && item.decision === 'DISPUTED')) stop('Buyer receipt history did not retain the dispute after delivery completion.');
  const listed = (await request(`/business-document-evidence?subjectType=BUYER_RECEIPT&subjectId=${receipt.body.id}`, { token: buyer })).body;
  if (!listed.items.some((item) => item.id === buyerPhoto.id && item.lifecycleStatus === 'AVAILABLE')) stop('Buyer could not read the scanned receipt attachment.');
  console.log('Synthetic delivery completed; Driver POD photo and terminal-delivery Buyer receipt attachment passed real scanner and scoped API reads. Catalog unchanged.');
}
