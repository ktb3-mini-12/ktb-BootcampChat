import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration');
const roomsDuration = new Trend('rooms_duration');

// Test configuration
export const options = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },   // 30초 동안 50 VU까지
        { duration: '30s', target: 100 },  // 30초 동안 100 VU까지
        { duration: '30s', target: 200 },  // 30초 동안 200 VU까지
        { duration: '60s', target: 200 },  // 60초 동안 200 VU 유지
        { duration: '30s', target: 0 },    // 30초 동안 종료
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95% 요청이 2초 이내
    errors: ['rate<0.1'],              // 에러율 10% 미만
  },
};

const BASE_URL = __ENV.API_URL || 'https://chat.goorm-ktb-012.goorm.team';

export default function () {
  const userId = `k6-user-${__VU}-${__ITER}`;
  const email = `${userId}@test.com`;
  const password = 'Test1234!';

  // 1. Login or Register
  let loginRes = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
  );

  // If login fails (user doesn't exist), register
  if (loginRes.status === 401 || loginRes.status === 404) {
    const registerRes = http.post(
      `${BASE_URL}/api/auth/register`,
      JSON.stringify({ email, password, name: `K6 User ${__VU}` }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
    );

    check(registerRes, {
      'register success': (r) => r.status === 200 || r.status === 201,
    });

    loginRes = registerRes;
  }

  loginDuration.add(loginRes.timings.duration);

  const loginSuccess = check(loginRes, {
    'login status 200': (r) => r.status === 200,
    'login has token': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.token !== undefined;
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!loginSuccess);

  if (!loginSuccess) {
    sleep(1);
    return;
  }

  // Extract token
  let token;
  try {
    const body = JSON.parse(loginRes.body);
    token = body.token;
  } catch {
    sleep(1);
    return;
  }

  const authHeaders = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };

  // 2. Get rooms list
  const roomsRes = http.get(`${BASE_URL}/api/rooms`, {
    headers: authHeaders,
    timeout: '10s',
  });

  roomsDuration.add(roomsRes.timings.duration);

  const roomsSuccess = check(roomsRes, {
    'rooms status 200': (r) => r.status === 200,
  });

  errorRate.add(!roomsSuccess);

  // 3. Small delay between iterations
  sleep(Math.random() * 2 + 1); // 1-3 seconds
}
