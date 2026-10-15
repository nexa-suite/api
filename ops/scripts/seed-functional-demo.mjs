#!/usr/bin/env node
/** Adds named synthetic fixtures through the real local API; never imports catalog data. */
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8080/api/v1';
if (!process.argv.includes('--apply')) {
  console.error('Usage: node ops/scripts/seed-functional-demo.mjs --apply [--simulate-gps] [--receive-stock]');
  process.exit(2);
}
function stop(message) { throw new Error(message); }
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
