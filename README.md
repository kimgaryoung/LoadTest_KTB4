# 🚀 Load Test 프로젝트

부하테스트를 통해 서비스의 병목 지점을 확인하고,
측정 결과를 기반으로 인프라와 애플리케이션 구조를 개선했습니다.

초기에는 최소 구성으로 빠르게 Baseline을 확보한 뒤,
테스트 결과에 따라 필요한 부분을 단계적으로 개선했습니다.

---

## 🧪 초기 Baseline 구성

초기에는 다음과 같이 각 구성 요소를 1대씩 배치했습니다.

* Backend EC2 1대
* Frontend EC2 1대
* MongoDB 1대
* Redis 1대

이는 최종 구조가 아니라,
**서비스의 초기 처리 성능과 병목 지점을 확인하기 위한 Baseline 환경**이었습니다.

```text id="j8ssni"
최소 구성
   ↓
Baseline Test
   ↓
병목 확인
   ↓
원인 분석
   ↓
구조 개선
   ↓
재테스트
```

---

# 🔧 개선 사항

## 1. Redis 분리

### 문제

초기에는 하나의 Redis가 여러 역할을 담당하고 있었습니다.

```text id="8wtn2k"
Redis
 ├── Login Session
 ├── User Profile Cache
 └── Socket.IO Pub/Sub
```

서로 다른 워크로드가 하나의 Redis를 사용하면서
특정 기능의 부하가 다른 기능에도 영향을 줄 수 있는 구조였습니다.

예를 들어 프로필 캐시 데이터가 급격하게 증가해 Redis 메모리가 부족해질 경우,

```text id="uimagd"
Profile Cache 증가
        ↓
Redis Memory 부족
        ↓
Session Key 영향
        ↓
로그인 기능까지 영향 가능
```

처럼 캐시 부하가 세션 영역까지 영향을 줄 수 있습니다.

### 개선

Redis를 역할에 따라 분리했습니다.

```text id="2vshhu"
Redis A
└── Login Session

Redis B
└── User Profile Cache
```

이를 통해 서로 다른 Redis 워크로드 간의 간섭을 줄였습니다.

### 개선 효과

* 워크로드 간 성능 격리
* 장애 영향 범위 감소
* 메모리 정책 분리
* 데이터 특성에 따른 TTL 관리
* 특정 Redis의 부하가 다른 기능으로 전파되는 문제 감소

> Redis 분리의 핵심 목적은 Redis 자체를 더 빠르게 만드는 것이 아니라,
> **서로 다른 워크로드 사이의 영향을 격리하는 것**입니다.

---

<details>
<summary><b>📚 공부한 내용 — Redis 분리</b></summary>

<br>

### Redis는 Cache만 사용하는 것이 아니다

Redis는 Cache 외에도 다양한 목적으로 사용할 수 있습니다.

| Redis 사용                | 목적                        |
| ----------------------- | ------------------------- |
| Redis Cache             | DB 조회 결과 등을 저장해서 빠르게 재사용  |
| Redis Session           | 로그인 / 세션 정보 저장            |
| Redis Rate Limit        | 요청 횟수 저장                  |
| Socket.IO Redis Adapter | 여러 Socket.IO 서버 사이 이벤트 전달 |
| Redis Queue / Stream    | 비동기 메시지 처리                |

---

### Redis Adapter

Socket.IO 서버가 한 대라면 해당 서버의 메모리 안에서
연결된 Socket을 확인하고 바로 Broadcast할 수 있습니다.

하지만 서버가 여러 대라면 문제가 발생합니다.

```text id="yc8wr7"
Client 1
   │
   ▼
Server A


Client 2
   │
   ▼
Server B
```

같은 채팅방에 있는 사용자라도 서로 다른 서버에 연결되어 있다면
Server A는 Server B의 Socket 연결 정보를 직접 알 수 없습니다.

이 문제를 해결하기 위해 Redis Adapter를 사용할 수 있습니다.

```text id="cqtf4r"
Client A
   │
   ▼
Socket.IO Server A
   │
   │ Publish
   ▼
Redis Pub/Sub
   │
   │ Subscribe
   ▼
Socket.IO Server B
   │
   ▼
Client B
```

Redis Adapter는 여러 Socket.IO 서버 사이에서 이벤트를 전달하여
서버 간 Broadcast가 가능하도록 합니다.

<!-- Redis Adapter 이미지 삽입 -->

---

### Redis Pub/Sub

`Publish`

```text id="9jlry2"
Server A → Redis
```

보내는 서버가 Redis에게 다른 서버로 메시지를 전달해달라고 요청합니다.

`Subscribe`

```text id="6ytmhl"
Redis → Server B
```

받는 서버는 특정 Channel을 구독하고 있다가
메시지가 발행되면 전달받습니다.

---

### Redis를 분리해도 변하지 않는 부분

#### Fan-Out

Fan-Out은 하나의 이벤트를 여러 대상에게 전달하는 양입니다.

채팅방에 사용자가 100명 있다면

```text id="lx0xp3"
Message 1개
   ↓
100명에게 전달

Fan-Out = 100
```

입니다.

Redis를 분리했다고 해서

```text id="1g5dzj"
Fan-Out 100 → 50
```

이 되는 것은 아닙니다.

즉 Redis 분리는 Fan-Out 자체를 감소시키는 최적화가 아닙니다.

---

#### RTT

RTT는 `Round Trip Time`으로
요청이 목적지까지 갔다가 응답이 다시 돌아오기까지 걸리는 왕복 시간입니다.

```text id="uslcc0"
Backend
   ↓
 Redis
   ↓
Backend
```

Redis를 분리해도

* 서버와 Redis 사이의 네트워크 거리
* 요청 / 응답 횟수

자체가 줄어드는 것은 아닙니다.

따라서 Redis의 CPU 경합이 원인이었다면 개선될 수 있지만
네트워크 RTT 자체가 Redis 분리만으로 감소하는 것은 아닙니다.

---

### KEYS vs SCAN

Redis에서 Pattern 기반으로 Key를 탐색할 때 사용할 수 있습니다.

#### KEYS

```bash id="0ac2em"
KEYS pattern
```

조건에 맞는 Key를 한 번에 조회합니다.

장점:

* 구현이 간단함

단점:

* Key가 많으면 Redis를 오래 점유할 수 있음
* O(N) 연산이므로 데이터가 많을수록 주의 필요

#### SCAN

```bash id="bfvyd9"
SCAN cursor
```

Key를 나누어서 조회합니다.

장점:

* 한 번의 명령으로 Redis를 오래 점유하지 않음

단점:

* 여러 번 요청해야 함

Redis를 분리했다고 해서 SCAN 자체가 빨라지는 것은 아닙니다.

> Redis 분리는 SCAN을 최적화한 것이 아니라
> **SCAN으로 인해 영향을 받는 범위를 감소시킨 것**입니다.

</details>

---

# 2. S3 도입

## 문제

E2E 테스트 과정에서 부하가 증가했을 때
파일 및 이미지 업로드 영역에서 실패가 발생했습니다.

기존 구조에서는 파일이 Backend를 거쳐 저장되는 구조였습니다.

```text id="mrr2ep"
Client
   ↓
Backend
   ↓
File Storage
```

파일은 일반적인 API 요청보다 데이터 크기가 크기 때문에
업로드 요청이 증가하면 Backend의 처리 자원을 오래 점유할 수 있습니다.

### 개선

파일 업로드 트래픽을 애플리케이션 서버에서 분리하기 위해
S3를 활용하는 구조로 변경했습니다.

```text id="7ka5bn"
Client
   │
   ├── 일반 API 요청 ──→ Backend
   │
   └── 파일 업로드 ───→ S3
```

<!-- S3 도입 전/후 이미지 삽입 -->

### 개선 효과

* 파일 전송 부하를 Backend에서 분리
* Application Server의 파일 처리 부담 감소
* 파일 저장 영역과 애플리케이션 영역 역할 분리

---

<details>
<summary><b>📚 공부한 내용 — 파일 업로드 구조</b></summary>

<br>

파일 업로드는 일반적인 JSON API 요청보다 전송하는 데이터 크기가 큽니다.

기존처럼

```text id="az0tzh"
Client
   ↓
Backend
   ↓
Storage
```

구조를 사용하면 파일 데이터가 Backend를 반드시 통과해야 합니다.

따라서 부하가 커질수록 Backend가 파일 전송 작업을 처리해야 하는 시간이 증가할 수 있습니다.

파일을 별도의 Object Storage로 분리하면

```text id="5nk283"
             ┌──→ Backend
Client ──────┤
             └──→ S3
```

일반적인 API 처리와 파일 전송 역할을 분리할 수 있습니다.

</details>

---

# 3. Frontend 증설

## 문제

중간 부하테스트에서 메인 페이지의

* FCP
* LCP
* TTFB

값이 높게 나타났습니다.
<img width="2042" height="1172" alt="image" src="https://github.com/user-attachments/assets/c5887c2e-4738-482a-af00-ed22f08389fb" />

특히 FCP의 `p90` 값이 약 **6.44초**로 나타나
화면 렌더링이 지나치게 느린 상태였습니다.

처음에는 Image, JavaScript, Static Resource와 같은
정적 리소스가 병목일 가능성을 고려했습니다.

```text id="924jyn"
Static Resource 문제?
        ↓
CloudFront + S3 고려
```

하지만 TTFB 역시 함께 높았습니다.

TTFB가 높다는 것은 브라우저 렌더링 이전에
HTML의 첫 응답 자체가 느리게 도착하고 있다는 의미였습니다.

따라서 정적 리소스뿐만 아니라
**Frontend EC2 자체의 처리량이 병목일 가능성**이 있다고 판단했습니다.

### 개선

Frontend EC2를 추가했습니다.

```text id="s4wguz"
Before

Client
   ↓
Frontend EC2 1대
```

```text id="yynca6"
After

              Load Balancer
               /        \
              /          \
Frontend EC2 #1      Frontend EC2 #2
```

<!-- Frontend EC2 증설 이미지 삽입 -->

### 개선 목적

* Frontend 요청 처리량 증가
* 한 서버에 집중되는 요청 분산
* Frontend 병목 완화

---

<details>
<summary><b>📚 공부한 내용 — FCP / LCP / TTFB</b></summary>

<br>

### TTFB

`Time To First Byte`

Client가 요청을 보낸 뒤
Server로부터 첫 Byte를 받을 때까지 걸리는 시간입니다.

```text id="wzgigh"
Request
   ↓
Server 처리
   ↓
첫 Byte 도착
```

TTFB가 높다면 HTML이 브라우저에 도착하기 전 단계부터
응답이 늦어지고 있다고 볼 수 있습니다.

---

### FCP

`First Contentful Paint`

페이지에서 사용자에게 보이는 첫 번째 콘텐츠가
화면에 표시될 때까지의 시간입니다.

예:

* Text
* Image
* Canvas
* SVG

---

### LCP

`Largest Contentful Paint`

화면에서 가장 큰 주요 콘텐츠가 표시될 때까지의 시간입니다.

FCP와 LCP만 높고 TTFB가 정상적이라면

* Image
* JavaScript
* CSS
* Static Resource

문제를 의심할 수 있습니다.

하지만 이번 테스트에서는 TTFB도 함께 높았기 때문에
Frontend 서버의 처리량 자체도 병목 후보로 판단했습니다.

</details>

---

# 📈 개선 흐름

전체 개선 과정을 정리하면 다음과 같습니다.

```text id="1pymyl"
초기 Baseline
Backend 1
Frontend 1
MongoDB 1
Redis 1
       │
       ▼
부하테스트
       │
       ▼
병목 분석
       │
       ├── Redis 워크로드 간섭
       │       ↓
       │   Redis 분리
       │
       ├── 파일 업로드 실패
       │       ↓
       │     S3 도입
       │
       └── Frontend 처리량 병목
               ↓
          Frontend 증설
```

---

# 💡 회고

이번 프로젝트에서 가장 크게 느낀 점은
단순히 서버의 개수를 늘리는 것이 성능 개선의 시작점이 되어서는 안 된다는 것이었습니다.

```text id="rhevo3"
부하테스트
   ↓
병목 발견
   ↓
가설 설정
   ↓
지표 확인
   ↓
원인에 맞는 개선
   ↓
재테스트
```

특히 Socket.IO처럼 서버 내부 상태와 연결 정보가 중요한 서비스에서는
서버를 여러 대로 늘리는 것뿐만 아니라 **서버 간 이벤트가 어떻게 전달되는지**까지 고려해야 했습니다.

또한 Redis 분리, S3 도입, Frontend 증설처럼
각 개선이 **어떤 문제를 해결하기 위한 것인지 명확하게 정의한 뒤 적용하는 과정**이 중요하다는 것을 배웠습니다.
