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

## 🛠 기술 스택

| 분류 | 기술 |
|------|------|
| Gateway | Spring Cloud Gateway |
| 인증 | JWT |
| Rate Limit | Redis + RequestRateLimiter |
| 서비스 디스커버리 | Spring Cloud Netflix Eureka |
| 빌드 | Gradle |
