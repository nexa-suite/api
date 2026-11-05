#!/usr/bin/env node
/** Local synthetic stock enters through receiving commands, preserving the catalog. */
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdir, open, readFile, writeFile, rename, lstat, unlink } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8080/api/v1';
const flags = process.argv.slice(2);
const apply = flags.includes('--apply');
const plan = flags.includes('--plan');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const options = new Set(['--apply', '--plan', '--quantity', '--warehouse-id']);
let ownedLock;
class LocalStockError extends Error {}
function fail(message) { throw new LocalStockError(message); }
function option(name) {
  const index = flags.indexOf(name);
  return index < 0 ? null : flags[index + 1];
}
for (let index = 0; index < flags.length; index++) {
  const name = flags[index];
  if (!options.has(name) || flags.indexOf(name) !== index) fail('Unknown or repeated argument.');
  if (name === '--quantity' || name === '--warehouse-id') {
    if (!flags[index + 1] || flags[index + 1].startsWith('--')) fail(`${name} requires a value.`);
    index++;
  }
}
if (apply === plan) fail('Choose --plan or --apply. Optional: --quantity 100 --warehouse-id UUID.');
const quantity = Number(option('--quantity') ?? 100);
if (!Number.isInteger(quantity) || quantity < 1 || quantity > 10000) fail('Quantity must be an integer from 1 to 10000.');
const selectedWarehouse = option('--warehouse-id');
if (selectedWarehouse && !uuid.test(selectedWarehouse)) fail('Warehouse identity must be a UUID.');

function docker(args) {
  const result = spawnSync('docker', args, { cwd: root, encoding: 'utf8', maxBuffer: 12 * 1024 * 1024 });
  if (result.status !== 0) fail('Local Docker configuration or runtime is unavailable.');
  return result.stdout;
}
async function request(path, token, { method = 'GET', body, key } = {}) {
  const response = await fetch(base + path, {
    method, redirect: 'error', signal: AbortSignal.timeout(20000),
    headers: { 'Content-Type': 'application/json', 'X-Nexa-Client': 'NATIVE',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(key ? { 'Idempotency-Key': key } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) fail(`${method} ${path.split('?')[0]} returned HTTP ${response.status}; existing stock was preserved.`);
  const text = await response.text();
  return { value: text ? JSON.parse(text) : null, receivedQuantity: response.headers.get('X-Nexa-Received-Quantity') };
}
async function pages(path, token) {
  const result = [];
  let expectedTotal;
  for (let page = 0; page < 1000; page++) {
    const separator = path.includes('?') ? '&' : '?';
    const { value } = await request(`${path}${separator}page=${page}&size=100`, token);
    const total = value?.total ?? value?.totalItems;
    if (!Array.isArray(value?.items) || !Number.isSafeInteger(total) || total < 0 || value.page !== page) {
      fail('A paginated API response did not match the current contract.');
    }
    if (expectedTotal !== undefined && expectedTotal !== total) fail('The collection changed while paging; rerun after the concurrent work finishes.');
    expectedTotal = total;
    result.push(...value.items);
    if (result.length === total) return result;
    if (result.length > total || value.items.length === 0) fail('The paginated API collection was incomplete.');
  }
  fail('The paginated collection exceeded the local fixture limit.');
}
function day(value) { return value.toISOString().slice(0, 10); }
function temperature(value) {
  if (value === null || value === undefined) return null;
  const number = Number(value);
  if (!Number.isFinite(number)) fail('The catalog contains an invalid temperature bound.');
  return number;
}
function zoneSpec(sku) {
  const minimum = temperature(sku.temperatureMin);
  const maximum = temperature(sku.temperatureMax);
  if (minimum !== null && maximum !== null && minimum > maximum) fail('A SKU has incompatible temperature bounds.');
  const type = maximum !== null && maximum <= 0 ? 'FROZEN'
    : minimum !== null || maximum !== null ? 'CHILLED' : 'AMBIENT';
  const code = `DEMO-STOCK-${createHash('sha256').update(`${type}|${minimum}|${maximum}`).digest('hex').slice(0, 12).toUpperCase()}`;
  return { code, name: `Stock sintético ICISA · ${type}`, type, temperatureMin: minimum, temperatureMax: maximum };
}
async function verifyTrace(lot, sku, receipt, token) {
  if (!uuid.test(lot?.id ?? '') || lot.skuId !== sku.id || lot.warehouseId !== receipt.warehouseId
    || lot.zoneId !== receipt.zoneId || lot.batchNumber !== receipt.batchNumber || lot.unit !== receipt.unit
    || lot.productionDate !== receipt.productionDate || lot.expirationDate !== receipt.expirationDate
    || Date.parse(lot.receivedAt) !== Date.parse(receipt.receivedAt)) fail('A synthetic lot conflicts with its preserved receiving intent.');
  const movements = await pages(`/inventory/movements?lotId=${lot.id}`, token);
  const inbound = movements.filter((item) => item.type === 'INBOUND_RECEIPT');
  if (inbound.length !== 1 || inbound[0].skuId !== sku.id || inbound[0].unit !== receipt.unit
    || Number(inbound[0].quantity) !== quantity || Number(inbound[0].quantityBefore) !== 0
    || Number(inbound[0].quantityAfter) !== quantity) fail('The synthetic lot has no unambiguous receiving movement.');
}
async function main() {
  const endpoint = JSON.parse(docker(['context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']));
  if (typeof endpoint !== 'string' || !endpoint.startsWith('unix://')) fail('Only a local Docker socket is allowed.');
  const composition = JSON.parse(docker(['compose', '--env-file', '.env.local', '-f', 'ops/compose/modern.compose.yml', 'config', '--format', 'json']));
  const environment = composition.services?.['modern-api']?.environment;
  const runtime = JSON.parse(docker(['inspect', 'nexa-modern-api']))[0];
  const running = Object.fromEntries((runtime?.Config?.Env ?? []).map((entry) => {
    const index = entry.indexOf('='); return [entry.slice(0, index), entry.slice(index + 1)];
  }));
  if (!runtime?.State?.Running || !String(running.SPRING_PROFILES_ACTIVE).split(',').includes('local')
    || !String(environment?.SPRING_PROFILES_ACTIVE).split(',').includes('local')
    || environment.NEXA_DEV_WORKSPACE_SLUG !== 'icisa' || running.NEXA_DEV_WORKSPACE_SLUG !== 'icisa') {
    fail('The running local ICISA API and its Compose configuration are required.');
  }
  async function signIn(kind) {
    const identifier = environment[`NEXA_DEV_${kind}_EMAIL`];
    const password = environment[`NEXA_DEV_${kind}_PASSWORD`];
    if (typeof identifier !== 'string' || !identifier.endsWith('@icisa.test') || !password
      || running[`NEXA_DEV_${kind}_EMAIL`] !== identifier || running[`NEXA_DEV_${kind}_PASSWORD`] !== password) {
      fail('The configured synthetic identity does not match the running local API.');
    }
    const { value } = await request('/authentication/sign-in', null, { method: 'POST', body: {
      identifier, password, workspaceSlug: 'icisa', surface: 'PLATFORM',
    } });
    if (typeof value?.accessToken !== 'string' || value.session?.surface !== 'PLATFORM'
      || value.session.tenantSlug !== 'icisa' || value.session.workspaceSlug !== 'icisa'
      || !uuid.test(value.session.tenantId ?? '') || !uuid.test(value.session.workspaceId ?? '')) fail('The API did not confirm the expected ICISA authority.');
    return value;
  }
  const owner = await signIn('OWNER');
  const warehouseUser = await signIn('WAREHOUSE');
  if (owner.session.tenantId !== warehouseUser.session.tenantId
    || owner.session.workspaceId !== warehouseUser.session.workspaceId) fail('Catalog and Warehouse authority have different scopes.');
  const warehouses = await pages('/warehouses', warehouseUser.accessToken);
  const warehouse = warehouses.find((item) => selectedWarehouse ? item.id === selectedWarehouse : item.code === 'ICISA-COLD-01');
  if (!warehouse || !uuid.test(warehouse.id ?? '') || warehouse.status !== 'ACTIVE') fail('The selected active Warehouse is not granted to this operator.');
  const skus = await pages('/skus', owner.accessToken);
  const active = skus.filter((item) => item.status === 'ACTIVE');
  if (new Set(active.map((item) => item.id)).size !== active.length || active.length === 0) fail('The active catalog SKU collection is empty or ambiguous.');
  for (const sku of active) {
    if (!uuid.test(sku.id ?? '') || typeof sku.unitOfMeasure !== 'string' || !sku.unitOfMeasure.trim()) fail('An active SKU lacks an authoritative identity or unit; configure the catalog first.');
    zoneSpec(sku);
  }
  const directory = resolve(root, '.local/catalog-stock');
  const statePath = resolve(directory, `${owner.session.tenantId}-${owner.session.workspaceId}-${warehouse.id}.json`);
  const scope = { tenantId: owner.session.tenantId, workspaceId: owner.session.workspaceId, warehouseId: warehouse.id };
  if (apply) {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const stat = await lstat(directory);
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('The receiving journal directory is invalid.');
    const lockPath = `${statePath}.lock`;
    let lock;
    try { lock = await open(lockPath, 'wx', 0o600); }
    catch (error) {
      if (error.code === 'EEXIST') fail('Local receiving is running or was interrupted; reconcile its journal lock before restarting.');
      throw error;
    }
    ownedLock = lockPath;
    try { await lock.writeFile(`${process.pid}\n`); } finally { await lock.close(); }
  }
  let state = { version: 1, base, ...scope, quantity, intents: {} };
  try {
    const stat = await lstat(statePath);
    if (!stat.isFile() || stat.isSymbolicLink()) fail('The local receiving journal must be a regular file.');
    state = JSON.parse(await readFile(statePath, 'utf8'));
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (state.version !== 1 || state.base !== base || state.quantity !== quantity
    || Object.entries(scope).some(([key, value]) => state[key] !== value)
    || !state.intents || typeof state.intents !== 'object' || Array.isArray(state.intents)) fail('The receiving journal has a different scope, quantity or format.');
  async function save() {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const stat = await lstat(directory);
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail('The receiving journal directory is invalid.');
    const temporary = `${statePath}.${randomUUID()}.tmp`;
    await writeFile(temporary, JSON.stringify(state, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    await rename(temporary, statePath);
  }
  const lots = await pages(`/inventory/lots?warehouseId=${warehouse.id}`, warehouseUser.accessToken);
  const zones = await pages(`/warehouses/${warehouse.id}/zones`, warehouseUser.accessToken);
  const summary = { mode: apply ? 'apply' : 'plan', ...scope, activeSkus: active.length, quantityPerSku: quantity,
    existingTracedLots: 0, receivedLots: 0, pendingReceipts: 0 };
  for (const sku of active) {
    const spec = zoneSpec(sku);
    const batchNumber = `LOCAL-TRACE-${sku.id}`;
    const matching = lots.filter((item) => item.batchNumber === batchNumber);
    if (matching.length > 1) fail('More than one synthetic lot matches a SKU; no duplicate receipt was sent.');
    let intent = state.intents[sku.id];
    if (matching.length === 1) {
      if (!intent) fail('An existing synthetic lot has no receiving journal; reconcile it before retrying.');
      await verifyTrace(matching[0], sku, intent.receipt, warehouseUser.accessToken);
      summary.existingTracedLots++;
      continue;
    }
    if (!apply) { summary.pendingReceipts++; continue; }
    let zone = zones.find((item) => item.code === spec.code);
    if (!zone) {
      zone = (await request(`/warehouses/${warehouse.id}/zones`, warehouseUser.accessToken, { method: 'POST', body: spec })).value;
      zones.push(zone);
    }
    if (zone.status !== 'ACTIVE' || zone.warehouseId !== warehouse.id || zone.type !== spec.type
      || temperature(zone.temperatureMin) !== spec.temperatureMin || temperature(zone.temperatureMax) !== spec.temperatureMax) fail('An existing synthetic storage zone conflicts with the SKU requirements.');
    if (!intent) {
      const received = new Date(Date.now() - 60000);
      const shelfLife = Number.isSafeInteger(sku.shelfLifeDays) && sku.shelfLifeDays > 0 ? sku.shelfLifeDays : 30;
      const receipt = { warehouseId: warehouse.id, zoneId: zone.id, skuId: sku.id,
        catalogItemId: sku.legacyCatalogItemId || null, batchNumber, quantity, unit: sku.unitOfMeasure,
        productionDate: day(new Date(received.getTime() - 86400000)), receivedAt: received.toISOString(),
        expirationDate: day(new Date(received.getTime() + shelfLife * 86400000)),
        temperatureReading: spec.temperatureMin !== null && spec.temperatureMax !== null
          ? (spec.temperatureMin + spec.temperatureMax) / 2 : spec.temperatureMin ?? spec.temperatureMax ?? 20,
        notes: 'Stock sintético de ICISA para pruebas funcionales; no representa existencias físicas.',
      };
      intent = { commandId: randomUUID(), receipt };
      state.intents[sku.id] = intent;
      await save();
    }
    if (!uuid.test(intent.commandId ?? '') || intent.receipt?.skuId !== sku.id || intent.receipt?.quantity !== quantity
      || intent.receipt?.warehouseId !== warehouse.id || intent.receipt?.zoneId !== zone.id || intent.receipt?.unit !== sku.unitOfMeasure
      || intent.receipt?.batchNumber !== batchNumber) fail('The pending receiving intent no longer matches the catalog or Warehouse.');
    const result = await request('/inventory/inbound-receipts', warehouseUser.accessToken,
      { method: 'POST', key: intent.commandId, body: intent.receipt });
    if (result.receivedQuantity === null || Number(result.receivedQuantity) !== quantity) fail('The API did not acknowledge the immutable received quantity; retain the journal and retry with the matching API.');
    await verifyTrace(result.value, sku, intent.receipt, warehouseUser.accessToken);
    const replay = await request('/inventory/inbound-receipts', warehouseUser.accessToken,
      { method: 'POST', key: intent.commandId, body: intent.receipt });
    if (replay.value?.id !== result.value.id || replay.receivedQuantity === null || Number(replay.receivedQuantity) !== quantity) fail('The same-command replay was not confirmed.');
    await verifyTrace(replay.value, sku, intent.receipt, warehouseUser.accessToken);
    intent.lotId = result.value.id;
    await save();
    summary.receivedLots++;
  }
  console.log(JSON.stringify(summary, null, 2));
}
main().catch((error) => {
  console.error(error instanceof LocalStockError ? error.message : 'Local stock preparation failed; inspect local runtime access and the receiving journal.');
  process.exitCode = 1;
}).finally(async () => {
  if (ownedLock) {
    try { await unlink(ownedLock); }
    catch { console.error('The local receiving journal lock could not be removed; reconcile it before restarting.'); process.exitCode = 1; }
  }
});
