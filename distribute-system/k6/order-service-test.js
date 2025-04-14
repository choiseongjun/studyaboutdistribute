import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";
import { randomString } from "https://jslib.k6.io/k6-utils/1.2.0/index.js";

// 커스텀 메트릭 정의
const errorRate = new Rate("errors");
const orderProcessingTime = new Trend("order_processing_time");
const orderSuccessRate = new Rate("order_success");
const totalOrders = new Counter("total_orders");
const httpErrors = new Counter("http_errors");

// 테스트 설정
export const options = {
  vus: 5,
  duration: "30s",
  thresholds: {
    http_req_duration: ["p(95)<1000"],
    http_req_failed: ["rate<0.2"],
  },
  ext: {
    loadimpact: {
      name: "Order Service Load Test",
      projectID: 1,
    },
  },
};

// 테스트 데이터 생성
function generateOrderId() {
  return `order-${Math.random().toString(36).substring(2, 15)}`;
}

// 주문 처리 함수
function processOrder(orderId) {
  const startTime = Date.now();
  totalOrders.add(1);

  const response = http.get(
    `http://host.docker.internal:8080/orders/${orderId}/process`,
    {
      headers: {
        "Content-Type": "application/json",
      },
    }
  );

  const duration = Date.now() - startTime;
  orderProcessingTime.add(duration);

  const checkResult = check(response, {
    "status is 200": (r) => r.status === 200,
    "response contains orderId": (r) => r.body && r.body.includes(orderId),
  });

  if (!checkResult) {
    httpErrors.add(1);
  }

  errorRate.add(!checkResult);
  orderSuccessRate.add(checkResult);

  return checkResult;
}

// 메인 테스트 함수
export default function () {
  const orderId = randomString(10);
  const url = "http://host.docker.internal:8080/api/orders";
  const payload = JSON.stringify({
    orderId: orderId,
    productId: "test-product",
    quantity: 1,
    userId: "test-user",
  });

  const params = {
    headers: {
      "Content-Type": "application/json",
    },
    timeout: "30s",
  };

  try {
    const response = http.post(url, payload, params);

    check(response, {
      "is status 200": (r) => r.status === 200,
      "response time < 1000ms": (r) => r.timings.duration < 1000,
    });

    if (response.status !== 200) {
      console.log(
        `Request failed with status ${response.status}: ${response.body}`
      );
    }
  } catch (error) {
    console.log(`Request failed with error: ${error.message}`);
  }

  sleep(2);
}

// 테스트 시작 전 실행
export function setup() {
  console.log("Starting load test...");
}

// 테스트 종료 후 실행
export function teardown(data) {
  console.log("Load test completed");
}

// 테스트 결과 요약
export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: " ", enableColors: true }),
    "./summary.json": JSON.stringify(data, null, 2),
  };
}
