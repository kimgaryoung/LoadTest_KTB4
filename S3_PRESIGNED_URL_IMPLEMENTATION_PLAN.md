# S3 Presigned URL 도입 계획

> 작성 기준: 2026-08-12, branch `레디스-분리`, commit `a217eb824`
>
> 상태: 2026-08-12 코드 반영. 실제 S3 bucket 통합/E2E와 동일 조건 성능 측정 전이며, 아래 성능 관련 서술은 모두 **가설**이다.
>
> 목표: 원본 파일 바이트를 애플리케이션 서버가 중계하지 않도록 private S3와 presigned PUT/GET을 도입하면서, 현재 파일 URL·인가·원본 바이트·E2E 계약과 로컬 저장소 롤백 경로를 보존한다.

## 1. 범위와 비범위

### 이번 도입 범위

- 채팅 첨부와 프로필 이미지의 브라우저 → S3 직접 업로드(presigned PUT)
- private S3 객체의 제한 시간 GET URL 발급
- 업로드 준비, S3 업로드 완료 검증, DB 반영의 3단계 계약
- `local`/`s3` 저장소 선택과 즉시 롤백 설정
- 미완료·미연결 업로드의 정리 정책과 관측 지표
- 현재 E2E, 프런트엔드, Socket.IO, 다운로드 계약을 보존하는 회귀 테스트

### 이번 도입의 비범위

- CloudFront/CDN 도입
- 원본 이미지 압축 또는 WebP 변환
- 썸네일 생성
- 5MB를 넘는 multipart upload
- 기존 로컬 파일의 일괄 S3 마이그레이션
- 기존 E2E 파일 또는 E2E 시나리오의 변경

원본 압축은 현재 채팅 다운로드의 파일 크기와 SHA-256 계약을 깨므로 별도 파생 이미지 작업으로만 다룬다. CDN과 기존 파일 마이그레이션도 S3 직접 업로드가 안정화된 뒤 별도 실험으로 분리한다.

## 2. 현재 코드에서 확인한 사실

| 영역 | 현재 구현 | 도입 시 의미 |
|---|---|---|
| 저장소 추상화 | `StoragePort`가 `put/open/delete/offloadUrl`을 제공한다. | S3 GET 오프로딩은 기존 확장점을 재사용할 수 있다. presigned PUT과 `HeadObject` 확인은 별도 포트가 필요하다. |
| 로컬 저장 | `LocalStorage`가 `FILE_STORAGE_TYPE=local`일 때 활성화된다. | 기본값과 롤백 경로로 유지한다. |
| 채팅 업로드 | `POST /api/files/upload`가 multipart를 받아 Spring을 거쳐 `chat/{safeFilename}`에 저장한 후 `File` 문서를 만든다. | 직접 업로드 모드에서는 준비 API와 S3 PUT을 먼저 수행하고, 기존 URL은 완료 확정 API로 유지한다. |
| 채팅 연결 | Socket.IO `fileData._id`를 받은 `ChatMessageHandler`가 파일 존재와 업로더를 확인한 뒤 메시지를 저장한다. | 새 파일은 완료 후 `PENDING`, 메시지 저장 후 `BOUND`가 되어야 고아 객체를 안전하게 구분할 수 있다. |
| 채팅 읽기 | `FileAccessService`가 파일 → 메시지 → 방 → 참가자 인가 후 5분 `offloadUrl`을 시도한다. | S3 URL은 반드시 이 인가 뒤에만 발급한다. |
| 프로필 업로드 | `POST /api/users/profile-image`가 multipart를 받아 `profiles/{safeFilename}`에 저장한다. 기존 객체를 먼저 삭제한다. | 새 객체 검증과 User 저장이 끝난 뒤 이전 객체를 삭제하도록 순서를 바꾼다. |
| 프로필 읽기 | 공개 `/api/files/profiles/{filename}`가 `StoragePort.open()`으로 원본을 반환한다. | API URL은 유지하고 private S3 presigned GET으로 302 응답한다. |
| 파일 제한 | 백엔드와 Spring multipart는 5MB, 채팅 프런트는 이미지 10MB/PDF 20MB로 불일치한다. | presign 발급 전에 서버의 5MB 계약으로 통일해야 한다. |
| AWS 의존성 | 현재 `pom.xml`에 AWS SDK가 없다. | AWS SDK for Java 2.x BOM과 `s3` 모듈을 명시적으로 추가한다. |

관련 소스:

- `apps/backend/src/main/java/com/ktb/chatapp/storage/StoragePort.java`
- `apps/backend/src/main/java/com/ktb/chatapp/storage/LocalStorage.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/LocalFileService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/FileAccessService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/UserService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/controller/ProfileImageController.java`
- `apps/frontend/services/fileService.js`
- `apps/frontend/components/ProfileImageUpload.js`

## 3. 고정해야 할 기능 계약

### 채팅 첨부

- 프런트가 최종적으로 받는 업로드 응답은 계속 `{ success, file }`이며 `file`의 `_id`, `filename`, `originalname`, `mimetype`, `size`, `uploadDate`를 유지한다.
- E2E가 기다리는 최종 성공 요청은 계속 `POST /api/files/upload`, HTTP 200이다.
- 미리보기 URL은 계속 `/api/files/view/{filename}`이다.
- 다운로드 URL은 계속 `/api/files/download/{filename}`이다.
- 참가자가 아닌 사용자는 presigned GET URL 발급 전에 403으로 거부한다.
- 미리보기와 다운로드는 업로드 원본과 같은 크기·SHA-256을 유지한다.
- 다운로드의 원본 파일명과 `Content-Disposition: attachment` 의미를 유지한다.

### 프로필 이미지

- E2E가 기다리는 최종 성공 요청은 계속 `POST /api/users/profile-image`, HTTP 200이다.
- 응답의 `imageUrl`은 계속 `/api/files/profiles/{filename}` 형식이다.
- 새로고침 후 같은 API URL로 이미지가 디코딩되어야 한다.
- 공개 프로필 URL의 익명 접근 계약은 유지하되 S3 bucket 자체는 public으로 만들지 않는다.
- 프로필 교체 실패 시 기존 이미지가 사라지면 안 된다.

### 스토리지와 보안

- bucket은 private이며 애플리케이션 IAM principal만 object 작업 권한을 가진다.
- presigned URL, AWS credential, bucket secret은 로그·DB·클라이언트 저장소에 영구 저장하지 않는다.
- 서버가 object key를 생성하고, 클라이언트가 임의 key나 다른 사용자의 업로드를 완료 처리하지 못하게 한다.
- URL 만료는 권한 만료일 뿐 객체 삭제가 아니다. 고아 객체 정리는 별도 코드와 정책으로 보장한다.

## 4. 목표 흐름

### 4.1 채팅 첨부

1. 프런트가 기존 확장자·MIME·5MB 제한을 검사하고 SHA-256을 계산한다.
2. `POST /api/files/presign`에 원본명, MIME, 크기, checksum을 보낸다. 준비 URL은 고정 E2E가 기다리는 `/api/files/upload` 문자열을 포함하지 않는다.
3. 서버가 사용자 인증과 메타데이터를 검증하고 충돌 불가능한 `chat/{safeFilename}` key 및 `UploadIntent`를 만든다.
4. 서버가 짧은 TTL의 presigned PUT URL과 반드시 전송할 signed headers를 반환한다.
5. 브라우저가 해당 URL로 원본을 직접 PUT한다.
6. 프런트가 기존 `POST /api/files/upload`에 `uploadIntentId`를 JSON으로 보내 완료를 요청한다.
7. 서버가 intent의 소유자·용도·상태·만료를 확인하고 `HeadObject`로 key, 크기, content type, checksum을 대조한다.
8. 서버가 기존 응답 모양의 `File` 메타데이터를 만들고 상태를 `PENDING`으로 기록한다.
9. 프런트가 기존과 같은 `fileData`를 Socket.IO `chatMessage`에 보낸다.
10. 메시지 저장 성공 후 파일을 `BOUND`로 전환한다.

기존 multipart `POST /api/files/upload`는 `consumes=multipart/form-data` 핸들러로 유지한다. 직접 업로드 완료는 같은 URL의 `consumes=application/json` 핸들러로 분리한다. 로컬 개발과 롤백은 multipart 경로를 사용하고, 프런트 fallback은 서버가 명시적으로 `DIRECT_UPLOAD_UNAVAILABLE`을 반환할 때만 허용한다. timeout이나 5xx에서 자동으로 Spring 중계 업로드로 전환하면 장애 시 서버 네트워크 부하가 갑자기 커질 수 있으므로 금지한다.

### 4.2 프로필 이미지

1. `POST /api/users/presign-profile-image`로 프로필 전용 intent와 PUT URL을 발급한다. 준비 URL은 고정 E2E가 기다리는 `/api/users/profile-image` 문자열을 포함하지 않는다.
2. 브라우저가 원본을 S3에 PUT한다.
3. 기존 `POST /api/users/profile-image`에 `uploadIntentId`를 JSON으로 보내 완료를 요청한다.
4. 서버가 `HeadObject` 검증 후 새 key로 User 문서를 저장하고 프로필 캐시를 갱신한다.
5. DB 저장 성공 뒤 이전 프로필 객체를 삭제한다. 삭제 실패는 새 프로필 성공을 되돌리지 않고 재시도/정리 대상으로 기록한다.
6. 응답은 기존 `ProfileImageResponse`와 `/api/files/profiles/{filename}` URL을 유지한다.
7. 공개 프로필 GET은 API URL에서 짧은 presigned GET으로 302한다.

### 4.3 업로드 상태

새 `UploadIntent` 문서는 최소 다음 정보를 가진다.

```text
id
ownerId
purpose            CHAT | PROFILE
objectKey
generatedFilename
originalFilename
contentType
expectedSize
checksumSha256
status             INITIATED | COMPLETED | BOUND | EXPIRED | FAILED
expiresAt
createdAt
completedAt
```

- `objectKey`에는 unique index를 둔다.
- 완료 API는 같은 intent에 대해 멱등이어야 한다. 이미 완료된 요청에는 같은 결과를 반환한다.
- 기존 `File` 문서는 상태가 없으므로 `uploadStatus`가 `null`인 과거 파일은 `BOUND`로 취급한다.
- 채팅 메시지 저장과 파일 `BOUND` 전환은 MongoDB 컬렉션 간 원자 트랜잭션을 전제로 하지 않는다. 정리 작업은 삭제 직전에 `MessageRepository.findByFileId()`를 다시 확인하여, 메시지에 연결된 파일을 삭제하지 않고 상태만 복구한다.
- Mongo TTL로 intent를 먼저 지우면 정리할 object key를 잃는다. 정리 작업이 `EXPIRED/FAILED` 객체 삭제를 완료한 뒤 intent를 삭제하거나, 정리 보존 기간보다 긴 TTL을 별도로 둔다.

## 5. 백엔드 설계

### 5.1 스토리지 경계

기존 `StoragePort`에는 AWS SDK 타입을 노출하지 않는다. 다음과 같이 역할을 분리한다.

- `StoragePort`: 기존 `put/open/delete/offloadUrl` 유지
- `DirectUploadPort`: `presignPut(UploadSpec)`과 `head(key)` 제공
- `S3Storage`: `StoragePort`, `DirectUploadPort` 구현
- `LocalStorage`: `StoragePort`만 구현
- `UploadIntentService`: intent 생성, 소유권/만료/멱등성, 완료 검증 담당
- `FileUploadCompletionService`: 검증된 intent를 `File` 또는 User에 반영

`S3Storage.open()`은 정상 S3 읽기 경로에서 사용하지 않는 것을 원칙으로 한다. 채팅은 `FileAccessService.offloadUrl()`, 프로필은 `ProfileImageController`의 redirect 경로를 사용한다. 단, 설정 오류 시 무제한 메모리 버퍼링으로 fallback하지 말고 명시적으로 실패시킨다.

### 5.2 설정

예정 설정은 다음과 같다. 값은 `.env.template`에 이름만 추가하고 실제 credential은 넣지 않는다.

```properties
file.storage.type=${FILE_STORAGE_TYPE:local}
file.direct-upload.enabled=${FILE_DIRECT_UPLOAD_ENABLED:false}
file.presign.put-ttl=${FILE_PRESIGN_PUT_TTL:10m}
file.presign.get-ttl=${FILE_PRESIGN_GET_TTL:5m}
file.pending-retention=${FILE_PENDING_RETENTION:24h}

app.s3.bucket=${S3_BUCKET:}
app.s3.region=${AWS_REGION:ap-northeast-2}
app.s3.endpoint=${S3_ENDPOINT:}
app.s3.path-style-access=${S3_PATH_STYLE_ACCESS:false}
```

- credential은 AWS SDK default credential provider chain을 사용한다. access key를 애플리케이션 설정 파일에 직접 적지 않는다.
- `S3_ENDPOINT`와 path-style은 LocalStack 같은 통합 테스트 환경에서만 사용한다.
- `FILE_STORAGE_TYPE=local` 또는 `FILE_DIRECT_UPLOAD_ENABLED=false`가 코드 롤백 스위치다.
- 잘못된 `s3` 설정에서 local로 조용히 fallback하지 않고 기동 실패를 노출한다.

### 5.3 S3 presign 규칙

- AWS SDK for Java 2.x의 `S3Client`와 `S3Presigner`를 singleton bean으로 구성하고 애플리케이션 종료 시 닫는다.
- PUT 서명에는 bucket, 서버 생성 key, content type, checksum 및 필요한 metadata/header를 포함한다.
- 브라우저는 응답에 포함된 signed headers를 그대로 전송한다. 서명된 `Content-Type`과 실제 PUT header가 달라지면 안 된다.
- key는 재사용하지 않는다. 같은 key의 PUT은 기존 객체를 덮어쓸 수 있기 때문이다.
- 완료 처리의 `HeadObject`는 적어도 content length, content type, checksum을 확인한다. ETag를 MD5로 가정하지 않는다.
- GET presign에는 미리보기 `inline`, 다운로드 `attachment`와 UTF-8 원본 파일명을 response override로 반영한다.
- URL 전체 query string을 로그로 남기지 않는다. 지표와 로그에는 intent ID, purpose, 결과, 지연, 크기 구간만 기록한다.

### 5.4 크기 제한의 주의점

presigned PUT은 발급 전에 선언 크기를 검증하고 완료 때 실제 크기를 검증할 수 있지만, 악의적인 클라이언트가 완료 호출 없이 더 큰 객체를 올리는 비용을 발급 단계에서 완전히 막는 정책 조건은 별도 검토가 필요하다. 첫 구현은 짧은 TTL, 사용자별 발급 rate limit, 무작위 key, 완료 `HeadObject`, 미완료 객체 정리로 방어한다.

대회 또는 운영 요구가 “S3가 업로드 자체를 하드 제한해야 함”이면 presigned PUT 대신 `content-length-range` 조건을 가진 presigned POST를 선택한다. 이 결정은 구현 시작 전에 작은 spike test로 확정하며, 선택 결과를 이 문서에 기록한다.

## 6. 프런트엔드 설계

### 채팅 `fileService`

- 공통 5MB, MIME, 확장자 검증
- Web Crypto로 SHA-256 계산
- presign 준비 요청
- S3 PUT과 progress/cancel 처리
- 기존 `/api/files/upload` 완료 요청
- 완료 응답을 현재 `useFileHandling`이 기대하는 형식으로 변환
- S3 PUT 실패, URL 만료, 완료 검증 실패를 서로 다른 오류 코드로 표시

### 프로필 `ProfileImageUpload`

- blob 미리보기 동작 유지
- 프로필 전용 presign → PUT → 기존 profile-image 완료 요청으로 전환
- 완료 전에는 저장 사용자 정보와 전역 `userProfileUpdate` 이벤트를 갱신하지 않음
- 실패 시 기존 서버 이미지로 복원하고 새 blob URL을 정리

axios interceptor의 JWT/session header를 S3 PUT에 전달하면 안 된다. S3 PUT은 인증 API용 axios instance가 아니라 별도 plain axios/fetch client를 사용하고, presign 응답이 지정한 header만 전송한다.

## 7. 인프라 계약

### Bucket CORS

허용 origin은 실제 프런트 origin만 열고 `*`를 사용하지 않는다.

```json
[
  {
    "AllowedOrigins": ["https://<frontend-origin>"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["content-type", "x-amz-checksum-sha256", "x-amz-meta-*"],
    "ExposeHeaders": ["ETag", "x-amz-checksum-sha256"],
    "MaxAgeSeconds": 300
  }
]
```

실제 signed headers가 확정된 뒤 최소 허용 목록으로 조정한다. 로컬 E2E origin도 별도 개발 bucket/LocalStack에만 추가한다.

### IAM과 bucket

- 애플리케이션 role: 대상 bucket/prefix의 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` 최소 권한. `HeadObject` 호출은 별도 IAM action이 아니라 객체의 `s3:GetObject` 권한으로 허용한다.
- bucket public access block 활성화
- 암호화 기본값 SSE-S3 또는 인프라 요구에 따른 SSE-KMS
- 운영 bucket과 테스트 bucket 분리
- pending/failed 객체 정리를 위한 애플리케이션 정리 작업 또는 검증된 lifecycle 정책

인프라 설정은 코드 저장소 밖에서 관리될 수 있으므로 실제 bucket policy, CORS, lifecycle의 소유 저장소와 배포 담당을 구현 전에 확인한다.

## 8. 단계별 변경 계획

한 단계에서 변수 하나만 바꾸고, 각 단계가 통과한 뒤 다음 단계로 진행한다.

### Phase 0 — 계약과 기준선 고정

- [ ] 현재 로컬 저장소에서 관련 백엔드 단위/컨트롤러 테스트 실행
- [ ] 채팅 첨부 및 프로필 이미지 E2E 실행
- [ ] 파일 포함 소규모 부하를 같은 파일 크기·사용자·시간으로 3회 측정
- [ ] API 서버 ingress/egress bytes, HTTP p95/p99, CPU, heap, 오류율 기록
- [ ] commit SHA, URL, 서버/부하기 사양, 실행 명령과 환경 변수 기록

### Phase 1 — S3 저장소와 GET 오프로딩

- [ ] AWS SDK BOM/`s3` 의존성 추가
- [ ] `S3Properties`, client/presigner bean, `S3Storage` 구현
- [ ] `FILE_STORAGE_TYPE=s3` 조건과 fail-fast 검증 추가
- [ ] 서버 경유 `put`은 유지한 채 채팅 GET의 기존 `offloadUrl` redirect 검증
- [ ] 프로필 API URL에서 presigned GET redirect 구현
- [ ] local과 s3 storage 선택 테스트, disposition/인가/404 테스트 추가
- [ ] 원본 크기와 SHA-256 E2E 통과 확인

이 단계는 저장 위치와 읽기 경로만 바꾼다. 직접 PUT은 아직 켜지 않는다.

### Phase 2 — 채팅 presigned PUT

- [ ] `UploadIntent` 모델/repository/index 추가
- [ ] `DirectUploadPort`와 S3 presign/head 구현
- [ ] 채팅 준비 API와 기존 upload URL의 JSON 완료 API 추가
- [ ] 완료 요청 멱등성, 소유권, 만료, size/type/checksum 검증
- [ ] `File.uploadStatus`와 과거 문서 호환 규칙 추가
- [ ] 프런트 `fileService`를 직접 PUT 흐름으로 전환
- [ ] 메시지 저장 뒤 `BOUND` 처리 및 정리 직전 메시지 재확인 추가
- [ ] 기존 multipart 경로와 loadtest 호환 유지

### Phase 3 — 프로필 presigned PUT

- [ ] 프로필 준비 API와 기존 profile-image URL의 JSON 완료 API 추가
- [ ] 새 User 저장 성공 후 이전 객체 삭제로 순서 변경
- [ ] 캐시 write-through와 삭제 실패 재시도/지표 추가
- [ ] `ProfileImageUpload`을 직접 PUT 흐름으로 전환
- [ ] 공개 API URL, 새로고침, 원본 크기 E2E 확인

### Phase 4 — 정리와 운영 안정화

- [ ] 만료 intent와 PENDING 파일 정리 scheduler 구현
- [ ] 삭제 전 메시지/User 참조 재확인
- [ ] 정리 실패 재시도와 최대 보존 기간 정의
- [ ] presign 발급, PUT 완료, head 검증, bind, cleanup counter/timer 추가
- [ ] S3 403/404/5xx, URL 만료, CORS 실패, DB 실패 장애 시험
- [ ] 운영 README와 `PROJECT_ANALYSIS.md`, `PERFORMANCE_IMPROVEMENT_PLAN.md` 갱신

## 9. 예상 변경 파일

정확한 이름은 구현 시 저장소 관례에 맞추되 역할은 다음과 같이 분리한다.

### 백엔드

- `apps/backend/pom.xml`
- `apps/backend/src/main/resources/application.properties`
- `apps/backend/.env.template`
- `apps/backend/src/main/java/com/ktb/chatapp/config/`의 S3 설정/bean
- `apps/backend/src/main/java/com/ktb/chatapp/storage/`의 direct upload port와 S3 구현
- `apps/backend/src/main/java/com/ktb/chatapp/model/`의 upload intent 및 파일 상태
- `apps/backend/src/main/java/com/ktb/chatapp/repository/`의 intent/file 상태 쿼리
- `apps/backend/src/main/java/com/ktb/chatapp/service/`의 준비·완료·정리 서비스
- `apps/backend/src/main/java/com/ktb/chatapp/controller/FileController.java`
- `apps/backend/src/main/java/com/ktb/chatapp/controller/ProfileImageController.java`
- `apps/backend/src/main/java/com/ktb/chatapp/controller/UserController.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/UserService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`

### 프런트엔드

- `apps/frontend/services/fileService.js`
- `apps/frontend/features/chat/room/useFileHandling.js`
- `apps/frontend/components/ProfileImageUpload.js`
- 관련 Vitest 파일

### 문서

- `README.md`
- `apps/backend/README.md`
- `PROJECT_ANALYSIS.md`
- `PERFORMANCE_IMPROVEMENT_PLAN.md`

기존 `e2e/` 파일은 변경 대상이 아니다.

## 10. 테스트 계획

### 백엔드 단위 테스트

- presign이 서버 생성 key, TTL, content type, checksum을 사용함
- 다른 사용자의 intent 완료 거부
- 만료/이미 완료/존재하지 않는 intent 처리
- `HeadObject` size/type/checksum 불일치 거부와 객체 정리 대상 기록
- 같은 완료 요청의 멱등 응답
- 프로필 DB 실패 시 기존 이미지 유지
- 프로필 성공 후 이전 객체 삭제, 삭제 실패 시 성공 응답과 재시도 기록
- 채팅 비참가자에게 GET presign을 발급하지 않음
- download/view의 attachment/inline response override 유지
- local 설정에서 기존 stream/multipart 경로 유지

### 컨트롤러 계약 테스트

- 준비 API의 인증, 400/401/413, 오류 코드
- 같은 기존 URL에 multipart와 JSON handler가 올바르게 선택됨
- 기존 업로드/프로필 완료 응답 스키마 유지
- profile API URL의 302와 잘못된 경로/404 계약
- 채팅 GET redirect의 302 `Location` 계약

### 통합 테스트

- LocalStack/Testcontainers 또는 격리된 테스트 bucket으로 PUT → HEAD → GET → DELETE
- 실제 브라우저 origin에서 OPTIONS/PUT CORS 확인
- UTF-8 원본 파일명의 download disposition 확인
- checksum이 다른 PUT/완료 요청 거부
- application context에서 `local`과 `s3` 구현이 동시에 선택되지 않음

### 기존 회귀 테스트

```bash
cd apps/backend
./mvnw -Dtest='*Storage*,*File*,*Profile*' test
./mvnw test

cd ../../
pnpm --dir apps/frontend test
pnpm --dir apps/frontend lint
pnpm run test:e2e

cd loadtest
pnpm test
```

실서비스나 공유 bucket을 대상으로 테스트하기 전에는 대상 URL, bucket, 허용 부하를 사용자에게 다시 확인한다.

## 11. 성능 실험

### 가설

브라우저가 S3로 직접 업로드하고 읽기 응답도 S3로 redirect되면 파일 부하에서 애플리케이션 서버의 ingress/egress bytes, Tomcat thread 점유, HTTP 응답 지연과 CPU 사용량이 감소한다.

### 동일하게 고정할 조건

- 같은 commit 계열의 local/server-proxy와 S3/direct-upload 빌드
- 같은 원본 파일과 SHA-256
- 같은 사용자 수, 업로드 빈도, 방 수, ramp pattern
- 같은 서버/부하기 사양과 리전
- warm-up 후 3회 이상 반복
- AI mention 제외

### 수집 지표

- 업로드 준비/완료 API p50/p95/p99와 오류율
- S3 PUT 성공률과 브라우저 관측 지연
- API 서버 ingress/egress bytes, CPU, heap, GC, Tomcat active thread
- 완료 `HeadObject` 지연과 오류 유형
- 업로드 요청 수 대비 COMPLETED/BOUND 수
- 만료·고아 객체 수와 정리 성공/실패
- E2E 원본 크기, SHA-256, 403 인가 회귀

### 초기 성공 기준

- 기존 E2E 회귀 0건
- 비참가자 접근과 타 사용자 intent 완료 우회 0건
- 원본 크기·SHA-256 불일치 0건
- 신규 고아 객체가 정리 보존 기간을 넘겨 남지 않음
- 파일 부하에서 같은 오류율 조건의 API 서버 network bytes가 유의미하게 감소
- p95 10% 이상 감소 또는 같은 SLO에서 처리량 10% 이상 증가 중 하나를 만족
- 비대상 핵심 지표 회귀 5% 이내

성능 기준을 만족하지 못하면 기능적 S3 마이그레이션 가치와 대회 성능 가치를 분리해서 판단한다.

## 12. 위험과 롤백

| 위험 | 방어 | 롤백 |
|---|---|---|
| presigned URL 유출/재사용 | 짧은 TTL, 무작위 1회성 key, 완료 멱등성, query 로그 금지 | direct upload flag off |
| CORS 또는 signed header 불일치 | 응답에 required headers 명시, 실제 브라우저 통합 테스트 | multipart 경로 사용 |
| 완료 전 이탈로 고아 객체 생성 | intent 상태, 만료 정리, 정리 지표 | 발급 중지 후 정리 계속 수행 |
| DB 성공/상태 전환 실패 사이의 불일치 | 정리 전 실제 Message/User 참조 재확인 | 기존 객체 삭제 중지 |
| 프로필 교체 중 기존 이미지 손실 | 새 객체 검증과 DB 저장 후 이전 객체 삭제 | local 또는 기존 multipart 모드 |
| S3 장애 때 대량 Spring fallback | 5xx/timeout 자동 fallback 금지 | 명시적 운영 전환 후 재배포/설정 변경 |
| 과거 로컬 파일 접근 불가 | 마이그레이션 전 혼합 읽기 전략 또는 전환 시점 분리 | `FILE_STORAGE_TYPE=local` |
| 비용 폭증형 큰 PUT | 발급 rate limit, 짧은 TTL, 정리, 필요 시 presigned POST | direct upload 발급 중지 |

롤백 시 이미 S3에 저장된 새 객체와 DB key를 local 구현이 읽을 수 없다는 점이 가장 큰 제약이다. 따라서 실제 전환 전에 다음 중 하나를 선택해야 한다.

1. 전환 기간에 `storageProvider`를 File/User 메타데이터에 저장하고 local/S3를 함께 읽는 복합 resolver를 둔다.
2. S3 전환 시점 이후 쓰기만 허용하고, 롤백 시 S3 읽기는 유지한 채 direct PUT만 끈다.

권장안은 2번이다. “직접 업로드 롤백”과 “S3 저장소 롤백”을 분리하여, 장애 시 `FILE_DIRECT_UPLOAD_ENABLED=false`로 서버 경유 S3 업로드로 먼저 되돌린다.

## 13. 구현 시작 전 결정 체크리스트

- [ ] 대회 파일 부하가 S3 도입 우선순위를 정당화하는 기준선이 있는가?
- [ ] presigned PUT의 비용 방어로 충분한가, hard size 조건 때문에 presigned POST가 필요한가?
- [ ] AWS region, private bucket, IAM role, CORS, lifecycle 담당 저장소/담당자는 어디인가?
- [ ] 운영 프런트와 개발/E2E origin 목록은 무엇인가?
- [ ] 기존 로컬 객체를 유지할 기간과 혼합 읽기 필요 여부는 무엇인가?
- [ ] checksum 알고리즘과 브라우저 계산 비용을 5MB fixture로 검증했는가?
- [ ] pending retention과 정리 주기는 얼마인가?
- [ ] S3 장애 시 허용할 동작은 업로드 실패인가, 운영자가 명시적으로 켜는 server-proxy S3 업로드인가?

이 체크리스트가 확정되면 Phase 1부터 한 vertical slice씩 구현하고, 각 Phase의 테스트와 측정 결과를 이 문서의 상태에 기록한다.

## 14. 공식 참고 자료

- [AWS SDK for Java 2.x — S3 presigned URL](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)
- [Amazon S3 — presigned URL 업로드](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html)
- [Amazon S3 — presigned URL 제한과 checksum](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Amazon S3 — 업로드 무결성 검사](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)
- [Amazon S3 API — bucket CORS](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketCors.html)
