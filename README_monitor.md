Markdown
# 🔍 분산 추적 및 로그 통합 모니터링 시스템 개요

---

## 1. 아키텍처 개요 (Architecture Overview)

전체 시스템은 **"계측(Agent) ➔ 수집 및 라우팅(Alloy) ➔ 저장 및 시각화(Loki & Jaeger)"**의 3단계 파이프라인으로 구성됩니다.
개발 편의성을 위해 스프링 MVC 서버들은 인텔리제이(Windows Host)에서 직접 구동하며, 모니터링 백엔드 인프라만 도커 컴포즈(Docker Compose) 환경으로 격리하여 운영합니다.

### 🔄 데이터 흐름 (Data Flow)
* **Application (IntelliJ):** 서버가 구동될 때 부착된 OpenTelemetry Java Agent가 내부 가동 시간(Trace)과 콘솔 로그(Log)를 실시간으로 캡처합니다.
* **Standard Export:** 수집된 데이터는 글로벌 표준 규격인 OTLP(OpenTelemetry Protocol) 포맷으로 묶여 도커의 Alloy 포트(`4317`)로 단일 송신됩니다.
* **Grafana Alloy (Router):** 인프라의 중심에서 단일 엔드포인트로 들어온 OTLP 신호를 분해하여, 로그는 Loki로, 트레이스는 Jaeger로 각각 목적지에 맞게 라우팅(배송)합니다.
* **Storage & UI:** * **Jaeger**에서 API 호출 흐름과 병목 구간을 트래킹합니다.
    * **Grafana (Loki)**에서 해당 호출 시점에 매핑되는 가동 로그를 중앙 집중형으로 분석합니다.

---

## 2. 핵심 구성 파일 및 역할

프로젝트 내 모니터링 환경을 구성하는 핵심 파일들과 각각의 역할 정의입니다.

### ① docker-compose.yml
* **역할:** 모니터링 생태계(Loki, Jaeger, Grafana, Alloy)를 단일 가상 네트워크(`promotion_network`)로 묶어 컨테이너 간의 안전한 내부 통신을 보장합니다.
* **특징:** 외부(인텔리제이) 신호를 받아 하부 인프라로 전파해야 하므로, 수집기인 Alloy만 외부 OTLP 포트(`4317`)를 윈도우 호스트로 개방하고 나머지 저장소들은 내부망에 은닉합니다.

### ② config.alloy
* **역할:** 통합 가동 수집기(Collector)의 데이터 파이프라인 흐름을 제어하는 설정 파일입니다.
* **특징:** 인텔리제이에서 넘어오는 무거운 원격 신호를 메모리 버퍼에 모았다가 효율적으로 내보내는 배치(Batch) 프로세싱을 수행하며, Jaeger와 Loki 백엔드의 정확한 엔드포인트 주소를 관리합니다.

### ③ opentelemetry-javaagent.jar
* **역할:** 어플리케이션 런타임에 동적으로 개입하여 모니터링 코드를 주입하는 바이너리 에이전트입니다.
* **특징:** 비즈니스 로직(소스 코드)을 단 한 줄도 수정하지 않고 Spring MVC Controller 진입 시점, JDBC를 통한 DB 쿼리 실행 시간, Redis 분산 락 및 명령어 수행 지연을 자동으로 계측하여 자가 발송합니다.

---

## 3. 어플리케이션 가동 매개변수 (IntelliJ VM Options)

인텔리제이에서 스프링 앱 구동 시 에이전트 활성화 및 배송지를 지정하기 위해 사용하는 핵심 파라미터 정보입니다.

```text
-javaagent:C:/workspace/redistest/promotion/infrastructure/opentelemetry-javaagent.jar 
-Dotel.service.name=user-service 
-Dotel.exporter.otlp.endpoint=http://localhost:4318 
-Dotel.javaagent.debug=true
```

## 🪵 Grafana Loki 로그 조회 및 분석 가이드

OpenTelemetry Java Agent를 통해 수집된 로그는 단순한 텍스트가 아닌 구조화된 JSON 형태로 Loki에 적재됩니다. 효율적인 트러블슈팅을 위한 그라파나 Explore 조회 및 필터링 매뉴얼입니다.

---

### 1. Grafana Explore 필수 초기 설정 (Code 모드 전환)
그라파나(`http://localhost:3000`) Explore 메뉴의 기본 `Builder` 모드에서는 커스텀 파이프라인 연산자를 입력할 수 없습니다. 
* **설정 방법:** 쿼리 입력창 우측 상단의 **`Code`** 버튼(탭)을 클릭하여 자유 타이핑 모드로 전환합니다.

---

### 2. 핵심 LogQL 쿼리 매뉴얼

#### ① 특정 서비스의 전체 로그 조회
가장 기본적인 조회로, 에이전트가 부여한 표준 서비스명을 기반으로 긁어옵니다.
```text
{service_name="user-service"}