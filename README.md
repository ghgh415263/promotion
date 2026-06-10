# 🚀 프로젝트 아키텍처 개요 (Architecture Overview)

<img width="714" height="561" alt="image" src="https://github.com/user-attachments/assets/0d8fcd09-1162-4de3-abd9-c4667b603368" />


## 🧩 컴포넌트 설명

### Spring Cloud Gateway

모든 클라이언트 요청의 단일 진입점(port `8000`).

| 역할 | 설명 |
|------|------|
| **라우팅** | `/api/v1/users/**` 요청을 유저서버로 포워딩 |
| **JWT 필터** | `Authorization: Bearer ...` 헤더를 게이트웨이 레벨에서 검증. 유효하지 않으면 유저서버까지 요청이 전달되지 않음 |
| **Rate Limit** | Redis와 통신해 초과 요청 시 `429 Too Many Requests` 반환 |

### Redis

`RequestRateLimiter` 필터의 슬라이딩 윈도 카운터를 저장합니다.  
메모리 기반이라 속도가 빠르고, 게이트웨이 재시작 후에도 카운터 상태가 유지됩니다.
```
-- 인자 값 받기
local tokens_key = KEYS[1]           -- 토큰 개수를 저장할 Redis 키 (예: user1:tokens)
local timestamp_key = KEYS[2]        -- 마지막 충전 시간을 저장할 Redis 키 (예: user1:timestamp)

local rate = tonumber(ARGV[1])       -- 초당 토큰 충전 속도 (예: 초당 2개)
local capacity = tonumber(ARGV[2])   -- 버킷 최대 용량 (예: 최대 5개)
local now = tonumber(ARGV[3])        -- 현재 시간 (Unix Timestamp 초 단위)
local requested = tonumber(ARGV[4])  -- 이번 요청에서 소모할 토큰 수 (기본 1개)

-- 1. 기존 값 불러오기 (없으면 초기화)
local current_tokens = tonumber(redis.call('get', tokens_key))
if current_tokens == nil then
    current_tokens = capacity
end

local last_refreshed = tonumber(redis.call('get', timestamp_key))
if last_refreshed == nil then
    last_refreshed = 0
end

-- 2. 마지막 요청 이후 경과한 시간 계산
local delta = math.max(0, now - last_refreshed)

-- 3. 경과한 시간만큼 토큰 충전 (단, 최대 용량을 넘을 수 없음)
local refilled_tokens = math.min(capacity, current_tokens + (delta * rate))

-- 4. 토큰이 충분한지 확인 후 차감
local allowed = 0
if refilled_tokens >= requested then
    allowed = 1
    refilled_tokens = refilled_tokens - requested
end

-- 5. 갱신된 토큰 수와 현재 시간을 Redis에 다시 저장 (만료시간 설정)
redis.call('setex', tokens_key, 2, refilled_tokens)
redis.call('setex', timestamp_key, 2, now)

-- 6. 허용 여부 반환 (1이면 통과, 0이면 무시)
return { allowed, refilled_tokens }
```

### Eureka (Discovery Server)

유저서버가 기동 시 자신의 IP/포트를 등록합니다.  
게이트웨이는 유저서버 주소를 하드코딩하지 않고 Eureka를 통해 동적으로 조회합니다.  
인스턴스를 여러 개 띄우면 자동으로 로드밸런싱이 적용됩니다.

### User Server

실제 비즈니스 로직을 처리하는 서비스입니다.

| 엔드포인트 | 설명 |
|------------|------|
| `POST /api/v1/users/signup` | 이메일·비밀번호·이름 수신 → BCrypt 해싱 → DB 저장 |
| `POST /api/v1/users/login` | 자격증명 확인 → JWT 발급 |
| `GET  /api/v1/users/me` | JWT에서 userId 추출 → DB 조회 → 프로필 반환 |

---

## 🔄 요청 흐름

### 로그인 (`POST /login`)

```
클라이언트
  └─▶ Gateway: Rate Limit 체크 (Redis)
        └─▶ Gateway: JWT 없으므로 검증 스킵
              └─▶ Eureka에서 유저서버 주소 조회 (유레카에서 받아온 정보는 캐시한다.)
                    └─▶ User Server: 자격증명 확인 → JWT 발급
                          └─▶ 클라이언트에게 token 반환
```

### 내 정보 보기 (`GET /me`)

```
클라이언트 (Authorization: Bearer <token>)
  └─▶ Gateway: Rate Limit 체크 (Redis)
        └─▶ Gateway: JWT 검증 → 유효하지 않으면 여기서 차단
              └─▶ Eureka에서 유저서버 주소 조회 (유레카에서 받아온 정보는 캐시한다.)
                    └─▶ User Server: userId 추출 → DB 조회 → 프로필 반환
```

---

## 🚀 실행 방법

> 실행 순서가 중요합니다.

```bash
# 1. Eureka Discovery Server

# 2. Redis (Docker)

# 3. User Server

# 4. Gateway
```

---

## 📬 API 예시

### 회원가입

```http
POST http://localhost:8000/api/v1/users/signup
Content-Type: application/json

{
  "email": "test@example.com",
  "password": "password123",
  "name": "Test User"
}
```

### 로그인

```http
POST http://localhost:8000/api/v1/users/login
Content-Type: application/json

{
  "email": "test@example.com",
  "password": "password123"
}
```

### 내 정보 보기 (인증 필요)

```http
GET http://localhost:8000/api/v1/users/me
Authorization: Bearer <token>
```

---

## ⚡ 서킷 브레이커 (Circuit Breaker)

### 왜 필요한가 — 장애 전파 방지

유저 서버의 DB가 뻗거나 연산이 밀려 응답을 주지 못할 때, 서킷 브레이커가 없다면 다음 순서로 장애가 전파됩니다.

```
1. 게이트웨이가 유저 서버 응답을 기다리며 서버 자원(TCP 연결)을 계속 점유
2. 사용자가 재시도·새로고침을 반복 → 게이트웨이 자원 전체 고갈
3. 멀쩡하던 게이트웨이까지 다운 → 모든 마이크로서비스로 가는 길목 차단
```

> 💡 **서킷 브레이커의 해결책**  
> "유저 서버가 불안정하면, 게이트웨이 선에서 즉시 에러 처리(또는 Fallback 응답)하고 유저 서버로의 요청을 차단한다."

---

### 3가지 상태 (State Machine)

```
         실패율 ≥ 임계치                  대기 시간 초과
  Closed ──────────────▶ Open ──────────────────▶ Half-Open
    ▲                                                  │
    │         간보기 요청 성공                          │
    └──────────────────────────────────────────────────┘
                              │ 간보기 요청 실패
                              ▼
                            Open (타이머 리셋)
```

| 상태 | 설명 | 동작 |
|------|------|------|
| 🟢 **Closed** (정상) | 회로가 닫혀 요청이 정상 통과 | 성공/실패율을 백그라운드에서 기록. 실패율이 임계치(예: 50%)를 넘으면 Open으로 전환 |
| 🔴 **Open** (차단) | 유저 서버 장애로 판단, 요청 즉시 차단 | `CallNotPermittedException` 또는 Fallback 응답 반환. 유저 서버는 트래픽 없이 회복 시간 확보. 설정된 대기 시간(예: 30초) 후 Half-Open으로 전환 |
| 🟡 **Half-Open** (테스트) | 회복 여부를 확인하기 위해 소수 요청만 허용 | 간보기 요청이 **모두 성공** → Closed 복귀. **하나라도 실패** → Open으로 복귀 후 타이머 리셋 |

---

## 🔍 OpenTelemetry + Jaeger 분산 추적 (Distributed Tracing)

### 왜 필요한가 — 요청 흐름 추적

마이크로서비스 환경에서는 하나의 사용자 요청이 여러 서비스를 거치며 처리된다.

예를 들어 회원 조회 요청은 다음과 같은 흐름을 가진다.

```text
Client
  ↓
API Gateway
  ↓
User Service
  ↓
MySQL
```

만약 응답 속도가 느려지거나 오류가 발생했을 때 일반 로그만으로는 어느 구간에서 문제가 발생했는지 파악하기 어렵다.

```
1. API 응답 시간이 3초 이상 소요됨
2. Gateway 문제인지 User Service 문제인지 확인 불가
3. DB 조회 때문인지 외부 API 호출 때문인지 확인 불가
4. 여러 서비스 로그를 직접 조회하며 원인 분석 필요
```

> 💡 **OpenTelemetry의 해결책**
>
> "하나의 요청을 Trace 단위로 추적하여 서비스 간 호출 흐름과 각 구간의 수행 시간을 시각적으로 확인한다."

---

### 전체 흐름

```text
Client
  ↓
API Gateway
  ↓
User Service
  ↓
MySQL

       OpenTelemetry Java Agent
                    ↓
                OTLP Export
                    ↓
                 Jaeger
                    ↓
              Trace 조회
```

각 서비스는 OpenTelemetry Java Agent를 통해 자동 계측되며 생성된 Trace 데이터를 Jaeger로 전송한다.

Jaeger UI에서는 다음 정보를 확인할 수 있다.

* 요청 전체 처리 시간
* 서비스 간 호출 순서
* DB 쿼리 수행 시간
* 오류 발생 위치
* 병목 구간

---

### Java Agent 방식 선택 이유

OpenTelemetry는 코드 기반 계측과 Java Agent 기반 계측을 모두 지원한다.

본 프로젝트에서는 별도의 코드 수정 없이 자동 계측이 가능한 Java Agent 방식을 사용하였다.

장점

* 비즈니스 코드 수정 없음
* Spring MVC 자동 계측
* JDBC 자동 계측
* Eureka Client 호출 자동 계측
* 향후 Redis, Kafka, RestTemplate 등 자동 확장 가능

---

### Jaeger 실행

```yaml
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"
      - "4317:4317"
      - "4318:4318"
```

실행

```bash
docker compose up -d
```

접속

```text
http://localhost:16686
```

---

### OpenTelemetry Agent 실행 옵션

```text
-javaagent:$PROJECT_DIR$/infrastructure/opentelemetry-javaagent.jar 
-Dotel.service.name=api-gateway 
-Dotel.exporter.otlp.endpoint=http://localhost:4318 
-Dotel.javaagent.debug=true
```

| 옵션                          | 설명                          |
| --------------------------- | --------------------------- |
| -javaagent                  | OpenTelemetry Java Agent 적용 |
| otel.service.name           | Jaeger에 표시될 서비스 이름          |
| otel.exporter.otlp.protocol | OTLP 전송 프로토콜(gRPC)          |
| otel.exporter.otlp.endpoint | Jaeger OTLP Endpoint        |
| otel.logs.exporter          | 로그 Export 비활성화              |

---

### Trace 예시

회원 생성 요청

```text
POST /users
 ├─ UserController.createUser()
 ├─ INSERT users
 └─ Eureka Heartbeat
```

Jaeger에서는 각 구간의 수행 시간과 상태를 시각적으로 확인할 수 있다.

예시

```text
POST /users                 120ms
 ├─ INSERT users             15ms
 └─ HTTP Call (Eureka)        4ms
```

이를 통해 병목 지점과 장애 발생 위치를 빠르게 파악할 수 있다.



## 🛠 기술 스택

| 분류         | 기술                                |
| ---------- | --------------------------------- |
| Gateway    | Spring Cloud Gateway              |
| 인증         | JWT                               |
| Rate Limit | Redis + RequestRateLimiter        |
| 서비스 디스커버리  | Spring Cloud Netflix Eureka       |
| 분산 추적      | OpenTelemetry Java Agent + Jaeger |
| 빌드         | Gradle                            |

