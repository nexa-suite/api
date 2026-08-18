import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const workspace = __ENV.NEXA_LOAD_WORKSPACE || 'icisa';
const email = __ENV.NEXA_LOAD_EMAIL || 'owner@icisa.test';
const password = __ENV.NEXA_LOAD_PASSWORD || '';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  vus: Number(__ENV.K6_VUS || 4),
  duration: __ENV.K6_DURATION || '20s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
  },
};

const jsonParams = { headers: { 'Content-Type': 'application/json', Origin: 'http://localhost:4200' } };

export default function () {
  const preview = http.post(`${baseUrl}/api/v1/auth/workspace-previews`, JSON.stringify({ workspaceSlug: workspace }), jsonParams);
  check(preview, { 'workspace preview is controlled': (response) => [200, 403].includes(response.status) });

  const signIn = http.post(`${baseUrl}/api/v1/authentication/sign-in`, JSON.stringify({
    identifier: email,
    password,
    workspaceSlug: workspace,
    surface: 'PLATFORM',
  }), jsonParams);
  check(signIn, { 'load identity signs in': (response) => response.status === 200 });
  const token = signIn.status === 200 ? signIn.json('accessToken') : '';
  if (token) {
    const auth = { headers: { Authorization: `Bearer ${token}`, Origin: 'http://localhost:4200' } };
    const catalog = http.get(`${baseUrl}/api/v1/catalog-items?page=0&size=10`, auth);
    check(catalog, { 'catalog listing is available': (response) => response.status === 200 });
    const permissionCatalog = http.get(`${baseUrl}/api/v1/permissions/catalog`, auth);
    check(permissionCatalog, { 'permission catalog is available': (response) => response.status === 200 });
    const notifications = http.get(`${baseUrl}/api/v1/notifications/unread-count`, auth);
    check(notifications, { 'notification inbox is available': (response) => response.status === 200 });
  }
  sleep(0.1);
}
