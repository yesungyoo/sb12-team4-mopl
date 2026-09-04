# 모두의 플리 (MOPL) — 로컬 개발 환경

## 사전 준비
- Java 21
- Docker Desktop

## 실행 방법

1. 환경변수 파일 생성
   ```bash
   cp .env.example .env
   ```
   `.env` 파일을 열어서 `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD` 등 필요한 값을 채워주세요.

2. 인프라 컨테이너 실행
   ```bash
   docker compose up -d
   ```

3. 전체 컨테이너 상태 확인
   ```bash
   docker compose ps
   ```
   MySQL, Redis, Kafka, Elasticsearch가 `healthy` 상태여야 합니다.

4. Spring Boot 앱 실행 (모듈별)

   ⚠️ **중요**: `./gradlew bootRun`은 `.env` 파일을 자동으로 읽지 않습니다.
   반드시 **1번(.env 생성)이 끝난 뒤**, 환경변수를 먼저 등록하고 나서 실행하세요.

   ### macOS / Linux
   (새 터미널을 열 때마다 export를 다시 해줘야 합니다)
   ```bash
   set -a
   source .env
   set +a
   ./gradlew :api:bootRun --args='--spring.profiles.active=local'
   ```
   ```bash
   set -a
   source .env
   set +a
   ./gradlew :realtime:bootRun --args='--spring.profiles.active=local'
   ```

   ### Windows

   **방법 A (추천) — IntelliJ Run Configuration에 직접 등록**

   실행하려는 모듈(`ApiApplication` 등)의 Run Configuration을 열고:
   ```
   실행 → 구성 편집(Edit Configurations) → Environment variables
   ```
   에 아래 값들을 등록하세요.
   ```
   SPRING_PROFILES_ACTIVE=local
   DB_PASSWORD=...
   ※ `application-local.yml`에서 기본값이 없는 환경변수만 추가로 등록합니다.
   ※ `MYSQL_ROOT_PASSWORD`는 Docker Compose용이므로 Spring Run Configuration에는 필요하지 않습니다.
   ```
   이렇게 해두면 이후 그냥 Run 버튼만 눌러도 됩니다.

   **방법 B — PowerShell에서 직접 로딩**

   ```powershell
   Get-Content .env | ForEach-Object {
       if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
           [Environment]::SetEnvironmentVariable(
               $matches[1].Trim(),
               $matches[2].Trim(),
               'Process'
           )
       }
   }
   ```
   ```powershell
   .\gradlew :api:bootRun --args="--spring.profiles.active=local"
   ```

## 접속 정보

| 서비스 | 주소 |
|---|---|
| API | http://localhost:8080 |
| Realtime | http://localhost:8081 |
| MySQL | localhost:3306 |
| Redis | localhost:6379 |
| Kafka | localhost:9092 |
| Elasticsearch | http://localhost:9200 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

## 인프라 연결 상태 확인

```bash
curl http://localhost:8080/actuator/health
```

`api/src/main/resources/application-local.yml`에 `management.endpoint.health.show-details: always`가 설정되어 있어, MySQL/Redis/Elasticsearch 각각의 연결 상태를 한 번에 확인할 수 있습니다. (local 프로필 전용 설정이며, 운영 환경에는 영향 없습니다)

## 트러블슈팅

- **`./gradlew` 실행 시 `permission denied`**: `chmod +x gradlew` 실행 후 재시도
- **`Access denied for user 'mopl'@'...' (using password: NO)`**: `.env`를 만들기 전에 환경변수를 등록했거나, `.env` 수정 후 다시 등록을 안 한 경우입니다. **`.env` 생성 → 환경변수 등록 → 앱 실행** 순서를 반드시 지켜주세요.
- **MySQL 접속 시 비밀번호 오류(컨테이너 자체)**:
  ```bash
  docker compose down -v
  docker compose up -d
  ```
  위 두 명령어를 순서대로 실행해 볼륨까지 재생성하면 해결됩니다 (MySQL은 최초 생성 시점 값만 반영되기 때문).

  ⚠️ **주의**: `docker compose down -v`는 MySQL을 포함한 Docker Volume의 데이터를 삭제합니다. **아직 보존할 로컬 데이터가 없을 때만 사용하세요.** 개발이 진행되어 로컬에 테스트 데이터를 쌓아두셨다면, 이 명령어 대신 팀 채널에 먼저 문의해주세요.

- **Kafka CLI 명령어 `not found`**: `apache/kafka` 이미지는 스크립트가 `/opt/kafka/bin/`에 있음 (PATH 미등록)