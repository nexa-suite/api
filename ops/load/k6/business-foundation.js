import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const workspace = __ENV.NEXA_LOAD_WORKSPACE || 'icisa';
const businessVus = Number(__ENV.K6_BUSINESS_VUS || 4);
const businessDuration = __ENV.K6_BUSINESS_DURATION || '20s';
const platformOrigin = 'http://localhost:4200';
const portalOrigin = 'http://localhost:4300';

const users = {
  owner: {
    email: __ENV.NEXA_LOAD_OWNER_EMAIL || __ENV.NEXA_LOAD_EMAIL || 'owner@icisa.test',
    password: __ENV.NEXA_LOAD_OWNER_PASSWORD || __ENV.NEXA_LOAD_PASSWORD || '',
    surface: 'PLATFORM',
    origin: platformOrigin,
  },
  buyer: {
    email: __ENV.NEXA_LOAD_BUYER_EMAIL || 'buyer@icisa.test',
    password: __ENV.NEXA_LOAD_BUYER_PASSWORD || '',
    surface: 'PORTAL',
    origin: portalOrigin,
  },
  sales: {
    email: __ENV.NEXA_LOAD_SALES_EMAIL || 'sales@icisa.test',
    password: __ENV.NEXA_LOAD_SALES_PASSWORD || '',
    surface: 'PLATFORM',
    origin: platformOrigin,
  },
  warehouse: {
    email: __ENV.NEXA_LOAD_WAREHOUSE_EMAIL || 'warehouse@icisa.test',
    password: __ENV.NEXA_LOAD_WAREHOUSE_PASSWORD || '',
    surface: 'PLATFORM',
    origin: platformOrigin,
  },
  logistics: {
    email: __ENV.NEXA_LOAD_LOGISTICS_EMAIL || 'logistics@icisa.test',
    password: __ENV.NEXA_LOAD_LOGISTICS_PASSWORD || '',
    surface: 'PLATFORM',
    origin: platformOrigin,
  },
};

export const options = {
  setupTimeout: '5m',
  scenarios: {
    businessFoundation: {
      executor: 'constant-vus',
      vus: businessVus,
      duration: businessDuration,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
  },
};

function url(path) {
  return `${baseUrl}${path}`;
}

function requestParams(user, json = false, extraHeaders = {}) {
  return {
    headers: {
      ...(json ? { 'Content-Type': 'application/json' } : {}),
      Authorization: `Bearer ${user.token}`,
      Origin: user.origin,
      ...extraHeaders,
    },
    tags: { workflow: 'business-foundation' },
  };
}

function responseEtag(response, fallback = '"0"') {
  const headers = response && response.headers ? response.headers : {};
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === 'etag') return headers[key];
  }
  return fallback;
}

function parsed(response) {
  try {
    return response.json();
  } catch (_) {
    return {};
  }
}

function expectStatus(response, expected, label) {
  const statuses = Array.isArray(expected) ? expected : [expected];
  const ok = check(response, { [label]: (value) => statuses.includes(value.status) });
  if (!ok) {
    throw new Error(`${label} returned HTTP ${response.status}`);
  }
  return parsed(response);
}

function login(user, label) {
  const response = http.post(
    url('/api/v1/authentication/sign-in'),
    JSON.stringify({ identifier: user.email, password: user.password, workspaceSlug: workspace, surface: user.surface }),
    { headers: { 'Content-Type': 'application/json', Origin: user.origin }, tags: { workflow: 'business-foundation' } },
  );
  const body = expectStatus(response, 200, `${label} login`);
  if (!body.accessToken) {
    throw new Error(`${label} login did not return an access token`);
  }
  return { ...user, token: body.accessToken };
}

function futureDate() {
  return '2099-12-31';
}

function isoAfter(minutes) {
  return new Date(Date.now() + minutes * 60 * 1000).toISOString();
}

function createState(index) {
  const owner = login(users.owner, `owner-${index}`);
  const buyer = login(users.buyer, `buyer-${index}`);
  const sales = login(users.sales, `sales-${index}`);
  const warehouse = login(users.warehouse, `warehouse-${index}`);
  const logistics = login(users.logistics, `logistics-${index}`);

  const account = expectStatus(
    http.get(url('/api/v1/client-accounts/me'), requestParams(buyer)),
    200,
    `buyer-${index} client account`,
  );
  const addresses = expectStatus(
    http.get(url(`/api/v1/client-accounts/${account.id}/addresses`), requestParams(buyer)),
    200,
    `buyer-${index} address book`,
  );
  const address = addresses.find((candidate) => candidate.active) || addresses[0];
  if (!address) {
    throw new Error(`buyer-${index} has no active address`);
  }

  const catalog = expectStatus(
    http.get(url('/api/v1/catalog-items?page=0&size=25'), requestParams(buyer)),
    200,
    `buyer-${index} catalog`,
  );
  const item = (catalog.items || []).find((candidate) => candidate.sellableSkuId && candidate.catalogItemId);
  if (!item) {
    throw new Error(`buyer-${index} catalog has no sellable SKU`);
  }

  const keyPrefix = `business-foundation-${index}-${Date.now()}`;
  const draft = expectStatus(
    http.post(
      url('/api/v1/buyer/purchase-request-drafts'),
      JSON.stringify({ clientAccountId: account.id, requestedDeliveryDate: futureDate() }),
      requestParams(buyer, true),
    ),
    201,
    `buyer-${index} draft create`,
  );
  let draftEtag = responseEtag(draft.response || {});
  // k6 exposes headers on the response, so retain the response explicitly for ETag progression.
  const draftCreateResponse = http.get(url(`/api/v1/buyer/purchase-request-drafts/${draft.id}`), requestParams(buyer));
  expectStatus(draftCreateResponse, 200, `buyer-${index} draft reload`);
  draftEtag = responseEtag(draftCreateResponse, '"0"');

  const linesResponse = http.put(
    url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/lines`),
    JSON.stringify({ lines: [{ skuId: item.sellableSkuId, quantity: 1, unit: item.unitOfMeasure || 'UNIT' }] }),
    requestParams(buyer, true, { 'If-Match': draftEtag }),
  );
  expectStatus(linesResponse, 200, `buyer-${index} draft lines`);
  draftEtag = responseEtag(linesResponse, '"1"');

  const destinationResponse = http.put(
    url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/destination`),
    JSON.stringify({ addressId: address.id }),
    requestParams(buyer, true, { 'If-Match': draftEtag }),
  );
  expectStatus(destinationResponse, 200, `buyer-${index} draft destination`);
  draftEtag = responseEtag(destinationResponse, '"2"');

  const routeResponse = http.post(
    url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/route-previews`),
    JSON.stringify({ provider: 'LOCAL_DETERMINISTIC' }),
    requestParams(buyer, true, { 'If-Match': draftEtag }),
  );
  expectStatus(routeResponse, 200, `buyer-${index} draft route`);
  draftEtag = responseEtag(routeResponse, '"3"');

  const preferencesResponse = http.put(
    url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/preferences`),
    JSON.stringify({ paymentPreference: 'CARD_STRIPE', requestedDeliveryDate: futureDate() }),
    requestParams(buyer, true, { 'If-Match': draftEtag }),
  );
  expectStatus(preferencesResponse, 200, `buyer-${index} draft preferences`);
  draftEtag = responseEtag(preferencesResponse, '"5"');

  const review = expectStatus(
    http.get(url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/review`), requestParams(buyer)),
    200,
    `buyer-${index} draft review`,
  );
  if (!review.readyToSubmit) {
    throw new Error(`buyer-${index} draft is not ready to submit`);
  }

  const submitResponse = http.post(
    url(`/api/v1/buyer/purchase-request-drafts/${draft.id}/submissions`),
    null,
    requestParams(buyer, false, { 'If-Match': draftEtag, 'Idempotency-Key': `${keyPrefix}-submit` }),
  );
  const submittedDraft = expectStatus(submitResponse, 200, `buyer-${index} draft submit`);

  const requestResponse = http.get(url(`/api/v1/purchase-requests/${draft.id}`), requestParams(sales));
  expectStatus(requestResponse, 200, `sales-${index} purchase request`);
  let requestEtag = responseEtag(requestResponse, '"0"');
  const reviewRequest = http.post(
    url(`/api/v1/purchase-requests/${draft.id}/reviews`),
    null,
    requestParams(sales, false, { 'If-Match': requestEtag }),
  );
  expectStatus(reviewRequest, 200, `sales-${index} request review`);
  requestEtag = responseEtag(reviewRequest, '"1"');
  const approveRequest = http.post(
    url(`/api/v1/purchase-requests/${draft.id}/approvals`),
    null,
    requestParams(sales, false, { 'If-Match': requestEtag }),
  );
  expectStatus(approveRequest, 200, `sales-${index} request approval`);
  requestEtag = responseEtag(approveRequest, '"2"');
  const conversionResponse = http.post(
    url(`/api/v1/purchase-requests/${draft.id}/order-conversions`),
    '{}',
    requestParams(sales, true, { 'If-Match': requestEtag, 'Idempotency-Key': `${keyPrefix}-convert` }),
  );
  const order = expectStatus(conversionResponse, 201, `sales-${index} order conversion`);
  let orderEtag = responseEtag(conversionResponse, '"0"');
  const confirmationResponse = http.post(
    url(`/api/v1/sales-orders/${order.id}/confirmations`),
    null,
    requestParams(sales, false, { 'If-Match': orderEtag }),
  );
  expectStatus(confirmationResponse, 200, `sales-${index} order confirmation`);
  orderEtag = responseEtag(confirmationResponse, '"1"');

  const reservationResponse = http.post(
    url(`/api/v1/fulfillment-candidates/${order.id}/inventory-reservations`),
    null,
    requestParams(warehouse, false, { 'If-Match': orderEtag, 'Idempotency-Key': `${keyPrefix}-reserve` }),
  );
  const reservation = expectStatus(reservationResponse, 201, `warehouse-${index} FEFO reservation`);
  const reservationEtag = responseEtag(reservationResponse, '"0"');
  const dispatchResponse = http.post(
    url(`/api/v1/inventory-reservations/${reservation.id}/dispatch-orders`),
    null,
    requestParams(logistics, false, { 'If-Match': reservationEtag, 'Idempotency-Key': `${keyPrefix}-dispatch` }),
  );
  const dispatch = expectStatus(dispatchResponse, 201, `logistics-${index} dispatch creation`);

  let dispatchEtag = responseEtag(dispatchResponse, '"0"');
  const assignees = expectStatus(
    http.get(url('/api/v1/dispatch-assignees'), requestParams(logistics)),
    200,
    `logistics-${index} dispatch assignees`,
  );
  const assignee = assignees[0];
  if (!assignee || !assignee.id) {
    throw new Error(`logistics-${index} has no dispatch assignee`);
  }
  const preparationResponse = http.post(
    url(`/api/v1/dispatch-orders/${dispatch.id}/preparation-starts`),
    '{}',
    requestParams(logistics, true, { 'If-Match': dispatchEtag, 'Idempotency-Key': `${keyPrefix}-prepare` }),
  );
  expectStatus(preparationResponse, 200, `logistics-${index} dispatch preparation`);
  dispatchEtag = responseEtag(preparationResponse, '"1"');
  const assignmentResponse = http.post(
    url(`/api/v1/dispatch-orders/${dispatch.id}/assignments`),
    JSON.stringify({ responsibleMembershipId: assignee.id, vehicleReference: `K6-${index}`, routeName: 'K6 business route' }),
    requestParams(logistics, true, { 'If-Match': dispatchEtag, 'Idempotency-Key': `${keyPrefix}-assign` }),
  );
  expectStatus(assignmentResponse, 200, `logistics-${index} dispatch assignment`);
  dispatchEtag = responseEtag(assignmentResponse, '"2"');
  const windowStart = isoAfter(60);
  const scheduleResponse = http.post(
    url(`/api/v1/dispatch-orders/${dispatch.id}/schedules`),
    JSON.stringify({ deliveryWindowStart: windowStart, deliveryWindowEnd: isoAfter(180), eta: isoAfter(120) }),
    requestParams(logistics, true, { 'If-Match': dispatchEtag, 'Idempotency-Key': `${keyPrefix}-schedule` }),
  );
  expectStatus(scheduleResponse, 200, `logistics-${index} dispatch schedule`);
  dispatchEtag = responseEtag(scheduleResponse, '"3"');
  const readyResponse = http.post(
    url(`/api/v1/dispatch-orders/${dispatch.id}/route-readiness`),
    '{}',
    requestParams(logistics, true, { 'If-Match': dispatchEtag, 'Idempotency-Key': `${keyPrefix}-ready` }),
  );
  expectStatus(readyResponse, 200, `logistics-${index} dispatch route readiness`);
  dispatchEtag = responseEtag(readyResponse, '"4"');
  const receivableResponse = http.post(
    url('/api/v1/receivables'),
    JSON.stringify({ subjectType: 'SALES_ORDER', subjectId: order.id }),
    requestParams(owner, true, { 'Idempotency-Key': `${keyPrefix}-receivable` }),
  );
  const receivable = expectStatus(receivableResponse, 201, `owner-${index} receivable creation`);

  return {
    index,
    owner,
    buyer,
    sales,
    warehouse,
    logistics,
    accountId: account.id,
    addressId: address.id,
    item,
    draftId: draft.id,
    draftVersion: Number(String(responseEtag(submitResponse, '"6"')).replaceAll('"', '')),
    requestId: draft.id,
    orderId: order.id,
    orderEtag,
    reservationId: reservation.id,
    reservationEtag,
    dispatchId: dispatch.id,
    dispatchEtag,
    receivableId: receivable.id,
    submittedDraft,
    keyPrefix,
  };
}

export function setup() {
  const states = [];
  for (let index = 0; index < businessVus; index += 1) {
    states.push(createState(index));
  }
  console.log(`business_foundation_setup_states=${states.length}`);
  return states;
}

export default function (states) {
  const state = states[(__VU - 1) % states.length];
  const iterationKey = `${state.keyPrefix}-${__ITER}`;

  expectStatus(
    http.get(url('/api/v1/catalog-items?page=0&size=25'), requestParams(state.buyer)),
    200,
    'business catalog listing',
  );
  expectStatus(
    http.post(
      url('/api/v1/catalog/pricing-preview'),
      JSON.stringify({ items: [{ productId: state.item.productId, quantity: 1 }], asOf: new Date().toISOString() }),
      requestParams(state.buyer, true),
    ),
    200,
    'business pricing preview',
  );
  expectStatus(
    http.get(url(`/api/v1/buyer/purchase-request-drafts/${state.draftId}`), requestParams(state.buyer)),
    200,
    'business draft reload',
  );
  expectStatus(
    http.get(url(`/api/v1/buyer/purchase-request-drafts/${state.draftId}/review`), requestParams(state.buyer)),
    200,
    'business draft review',
  );
  expectStatus(
    http.get(url('/api/v1/purchase-requests?page=0&size=25'), requestParams(state.sales)),
    200,
    'business purchase request search',
  );
  expectStatus(
    http.post(
      url('/api/v1/sales-orders/manual'),
      JSON.stringify({
        clientAccountId: state.accountId,
        addressId: state.addressId,
        requestedDeliveryDate: futureDate(),
        paymentOption: 'CARD_STRIPE',
        priority: 'NORMAL',
        currency: 'PEN',
        lines: [{ skuId: state.item.sellableSkuId, catalogItemId: state.item.catalogItemId, quantity: 1, unit: state.item.unitOfMeasure || 'UNIT' }],
      }),
      requestParams(state.sales, true, { 'Idempotency-Key': `${state.keyPrefix}-manual-order` }),
    ),
    201,
    'business manual sales order',
  );
  expectStatus(
    http.get(url('/api/v1/sales-orders?page=0&size=25'), requestParams(state.sales)),
    200,
    'business sales order search',
  );
  expectStatus(
    http.get(url('/api/v1/inventory?page=0&size=25'), requestParams(state.warehouse)),
    200,
    'business warehouse availability',
  );
  expectStatus(
    http.get(url(`/api/v1/inventory-availability?catalogItemId=${encodeURIComponent(state.item.catalogItemId)}`), requestParams(state.warehouse)),
    200,
    'business inventory availability',
  );
  expectStatus(
    http.get(url('/api/v1/inventory/lots?page=0&size=25'), requestParams(state.warehouse)),
    200,
    'business FEFO lot search',
  );
  expectStatus(
    http.get(url('/api/v1/inventory-reservations?page=0&size=25'), requestParams(state.warehouse)),
    200,
    'business reservation search',
  );
  if (!state.reservationReplayed) {
    expectStatus(
      http.post(
        url(`/api/v1/fulfillment-candidates/${state.orderId}/inventory-reservations`),
        null,
        requestParams(state.warehouse, false, { 'If-Match': state.orderEtag, 'Idempotency-Key': `${state.keyPrefix}-reserve` }),
      ),
      201,
      'business FEFO reservation replay',
    );
    state.reservationReplayed = 1;
  }
  expectStatus(
    http.post(
      url(`/api/v1/inventory-reservations/${state.reservationId}/dispatch-orders`),
      null,
      requestParams(state.logistics, false, { 'If-Match': state.reservationEtag, 'Idempotency-Key': `${state.keyPrefix}-dispatch` }),
    ),
    201,
    'business dispatch creation replay',
  );
  expectStatus(
    http.get(url('/api/v1/dispatch-orders?page=0&size=25'), requestParams(state.logistics)),
    200,
    'business dispatch board',
  );
  if (!state.routeStarted) {
    const routeStartResponse = http.post(
      url(`/api/v1/dispatch-orders/${state.dispatchId}/route-starts`),
      '{}',
      requestParams(state.logistics, true, { 'If-Match': state.dispatchEtag, 'Idempotency-Key': `${state.keyPrefix}-route-start` }),
    );
    expectStatus(routeStartResponse, 200, 'business dispatch route start');
    state.dispatchEtag = responseEtag(routeStartResponse, state.dispatchEtag);
    state.routeStarted = 1;
  }
  if (!state.dispatchMutationVersion) {
    const temperature = http.post(
      url(`/api/v1/dispatch-orders/${state.dispatchId}/temperature-readings`),
      JSON.stringify({ value: -18, unit: 'CELSIUS', recordedAt: new Date().toISOString(), source: 'K6_BUSINESS' }),
      requestParams(state.logistics, true, { 'If-Match': state.dispatchEtag, 'Idempotency-Key': `${state.keyPrefix}-temperature` }),
    );
    expectStatus(temperature, 200, 'business temperature command');
    state.dispatchEtag = responseEtag(temperature, state.dispatchEtag);
    state.dispatchMutationVersion = 1;
  }
  expectStatus(
    http.get(url(`/api/v1/dispatch-orders/${state.dispatchId}/events`), requestParams(state.logistics)),
    200,
    'business dispatch events',
  );
  expectStatus(
    http.get(url('/api/v1/business-documents?page=0&size=25'), requestParams(state.buyer)),
    200,
    'business document listing',
  );
  const receivable = expectStatus(
    http.get(url(`/api/v1/receivables/${state.receivableId}`), requestParams(state.buyer)),
    200,
    'business receivable detail',
  );
  expectStatus(
    http.post(
      url(`/api/v1/receivables/${receivable.id}/payment-intents`),
      null,
      requestParams(state.buyer, false, { 'Idempotency-Key': `${state.keyPrefix}-payment-intent` }),
    ),
    201,
    'business payment intent',
  );
  expectStatus(
    http.get(url('/api/v1/notifications/unread-count'), requestParams(state.buyer)),
    200,
    'business notification inbox',
  );
  sleep(0.1);
}
