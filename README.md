# 모두의 플리 (MOPL) — 로컬 개발 환경

## 사전 준비
- Java 21
- Docker Desktop

## 실행 방법

1. 환경변수 파일 생성
   \`\`\`bash
   cp .env.example .env
   \`\`\`
   `.env` 파일을 열어서 `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD` 등 필요한 값을 채워주세요.

2. 인프라 컨테이너 실행
   \`\`\`bash
   docker compose up -d
   \`\`\`

3. 전체 컨테이너 상태 확인
   \`\`\`bash
   docker compose ps
   \`\`\`
   MySQL, Redis, Kafka, Elasticsearch가 `healthy` 상태여야 합니다.

4. Spring Boot 앱 실행 (모듈별)
   \`\`\`bash
   ./gradlew :api:bootRun --args='--spring.profiles.active=local'
   ./gradlew :realtime:bootRun --args='--spring.profiles.active=local'
   \`\`\`

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

## 트러블슈팅

- **`./gradlew` 실행 시 `permission denied`**: `chmod +x gradlew` 실행 후 재시도
- **MySQL 접속 시 비밀번호 오류**: `.env` 수정 후에는 `docker compose down -v && docker compose up -d`로 볼륨까지 재생성 필요 (MySQL은 최초 생성 시점 값만 반영됨)
- **Kafka CLI 명령어 `not found`**: `apache/kafka` 이미지는 스크립트가 `/opt/kafka/bin/`에 있음 (PATH 미등록)