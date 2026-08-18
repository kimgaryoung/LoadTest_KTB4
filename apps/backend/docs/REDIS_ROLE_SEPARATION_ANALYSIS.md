# 단일 Redis vs Redis A/B 분리 — 성능 차이 분석

> 전제: 이 저장소에는 A/B 분리 전후를 측정한 벤치마크가 없습니다.
> `loadtest/` 스크립트들(`ramp-up-test.js`, `participant-delta-benchmark.js`)은 Redis 구성을 변수로 다루지 않습니다.
> 아래는 실제 코드의 워크로드 특성에서 도출한 분석이고, 마지막에 실측 방법을 적었습니다.

## 두 인스턴스의 워크로드가 질적으로 다릅니다

**Redis A (auth)** — 짧고 O(1)인 Lua 위주

- `RedisSessionStore`: `VALIDATE_AND_TOUCH` Lua (EXISTS + HGET×3 + HSET + EXPIRE), `WRITE_SESSION` Lua
- `RedisRateLimitStore:19-22`: `INCR` + `EXPIRE` + `TTL` Lua
- 앞단에 Caffeine 로컬 캐시(`SessionService.java:26-28`)가 있어 hit이면 Redis를 아예 안 탐

**Redis B (realtime)** — 무겁고 팬아웃이 큰 작업

- `RedissonStoreFactory` → Socket.IO 세션 store + **노드 간 브로드캐스트 pub/sub**. 채팅 팬아웃이 전부 여기로 흐름
- `RedisChatDataStore.update()` (44-57행)는 `RLock` 사용 → Redisson 락은 내부적으로 pub/sub 채널로 unlock을 통지하므로, 경합 시 pub/sub 트래픽이 추가로 발생
- `cache:user-profile:v1:*` MGET / Lua MSET (TTL 60s)

## 이 코드베이스에서 분리 이득이 가장 큰 지점

`RedisChatDataStore.size()` (`RedisChatDataStore.java:63`)가 `getKeysByPattern("conn_users:userid:*")` — **키스페이스 전체 SCAN**입니다.
그리고 이게 `ConnectionLoginHandler.java:76`, `:116`에서 **접속/해제마다 `log.info` 인자로 호출**됩니다.

- **단일 Redis**: 이 SCAN이 `login_session:*` + `rate_limit:*` + `cache:user-profile:*`까지 전부 훑습니다.
  동접이 늘면 세션 키도 같이 늘어 스캔 대상이 커지고, 그 시간 동안 Redis 단일 스레드가 점유되어
  **로그인·레이트리밋 p99가 같이 튑니다**. 접속 폭주(reconnect storm) 때 auth 경로가 동반 열화되는 전형적 커플링입니다.
- **A/B 분리**: 스캔 대상이 realtime 키스페이스로 한정되고, auth 지연은 격리됩니다.

다만 이건 증상 완화입니다. 근본 해결은 SCAN 제거(카운터 키를 INCR/DECR로 유지하거나, 로그에서 `size()` 호출을 빼기)입니다.
분리했더라도 realtime 쪽에서는 여전히 O(전체 realtime 키) 비용을 접속마다 냅니다.

## 나머지 성능 차이

| 축 | 단일 Redis | A/B 분리 |
|---|---|---|
| CPU | 단일 스레드 1코어가 전부 처리 | 인스턴스 2개 = 코어 2개 활용 (**단, 다른 호스트일 때만**) |
| Tail latency | pub/sub 출력 버퍼 뒤에 세션 검증 명령이 줄섬 | auth 경로가 브로드캐스트 폭주와 무관해짐 |
| Eviction | maxmemory 도달 시 정책에 따라 **로그인 세션이 프로필 캐시에 밀려 evict될 수 있음** (`allkeys-lru`면 실제 로그아웃 발생) | 캐시 증가가 세션에 영향 없음 |
| 장애 반경 | 1대 죽으면 전부 정지 | auth 죽으면 로그인 불가 / realtime 죽으면 브로드캐스트 정지 |

## 분리의 비용 (성능적으로 손해거나 중립인 부분)

- **커넥션 2배**: WAS마다 Lettuce 풀 2개 + Redisson 1개. 노드 수가 많으면 Redis `connected_clients` 부담이 늘어납니다.
- **네트워크 홉 2곳**: RTT 자체는 줄지 않습니다. 처리량 병목이 Redis CPU가 아니라 RTT나 MongoDB면 **분리해도 아무 차이 없습니다.**
- **교차 원자성 상실**: 세션과 소켓 상태를 한 Lua/MULTI로 묶을 수 없습니다. 현재 코드는 그럴 필요가 없어 문제되지 않지만, 앞으로 제약이 됩니다.
- **fallback 비대칭**: 레이트리밋은 실패 시 fail-open(`RedisRateLimitStore.java:45-49`), 프로필 캐시는 Mongo fallback(`UserProfileCache.java:99-104`)인데,
  **Socket.IO store는 fallback이 없습니다** — realtime Redis가 죽으면 브로드캐스트가 그대로 멈춥니다. 장애 지점이 하나 늘어난 만큼 여기가 가장 약한 고리입니다.

## 이득이 없는 경우

- **동접이 낮을 때**: Redis 단일 인스턴스는 통상 수만 ops/s를 한 코어로 처리합니다.
  단일 Redis CPU가 20~30% 미만이면 분리해도 p99 차이는 측정 노이즈 수준입니다.
- **두 인스턴스를 같은 VM에 둘 때**: `docker-compose.yaml`의 로컬 구성(`redis` 6379 + `redis-realtime` 6380)은 같은 호스트라 CPU 경합이 그대로입니다.
  **기능 검증용이지 성능 이득은 없습니다.** 실제 이득은 `DEPLOY.md:117-125`처럼 호스트를 분리했을 때만 나옵니다.

## 실측하는 법 (이미 배선돼 있음)

exporter가 `role=auth|realtime` label로 붙어 있으므로(`prometheus.prod.yml:28-40`) 다음을 비교하면 됩니다.

- `redis_commands_duration_seconds_total / redis_commands_total` (역할별 평균 명령 지연)
- `instantaneous_ops_per_sec`, `redis_cpu_sys_seconds_total`, `blocked_clients`
- `redis_db_keys` (SCAN 비용의 대리 지표)
- 앱 쪽: `socketio.events.total`(`SocketIOConfig.java:90`), `SessionStoreMetrics`의 `validate_touch` 지연, `user.profile.cache.requests` hit/miss

`REALTIME_REDIS_HOST`를 auth 쪽으로 되돌리면 단일 구성으로 즉시 복귀되므로(`application.properties:30-35`의 폴백) A/B 비교 자체는 환경변수만으로 가능합니다.
`loadtest/ramp-up-test.js`로 동접을 올리며 두 구성의 p95/p99를 비교하는 게 가장 빠릅니다.
다만 **비교 전에 `size()`의 SCAN을 그대로 둘지 결정**하세요 — 분리 이득의 상당 부분이 그 한 줄에서 나오므로, SCAN을 먼저 없애면 분리 효과가 훨씬 작게 측정될 겁니다.

## 관련 문서

- `apps/backend/DEPLOY.md` — Redis A/B 엔드포인트 환경변수와 blue-green 전환 절차
- `apps/backend/monitoring/README.md` — `role=auth|realtime` exporter 구성
- `apps/backend/monitoring/grafana/provisioning/dashboards/redis-role-separation-dashboard.json` — 역할별 비교 대시보드
