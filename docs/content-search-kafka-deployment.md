# 콘텐츠 검색 Kafka 동기화 배포 및 운영

## 배포 순서

콘텐츠 검색 동기화 이벤트는 공통 Type ID `contentSearchSync`를 사용한다.
운영 배포에서는 이 Type ID와 기존 Type ID를 모두 역직렬화할 수 있는 API를
먼저 배포한 뒤 Batch를 배포해야 한다. Batch를 먼저 배포하면 구버전 API가
신규 Type ID를 처리하지 못할 수 있다.

권장 순서는 다음과 같다.

1. 공통 이벤트 계약과 Type ID 매핑을 포함한 API 배포
2. API Consumer 정상 기동 확인
3. Batch 배포 및 콘텐츠 수집 Job 실행

## Kafka 미설정 환경

`spring.kafka.bootstrap-servers`가 없으면 Batch Kafka Producer Bean은 생성되지
않는다. 이 경우 콘텐츠 DB 트랜잭션은 정상 커밋되지만 검색 동기화 이벤트는
발행되지 않으며 오류 로그가 남는다. 운영 환경에서는 Batch와 API 모두에
bootstrap 서버가 설정되었는지 배포 전에 확인해야 한다.

Transactional Outbox는 아직 없다. DB 커밋 후 첫 Kafka 발행이 실패한 이벤트는
Consumer DLT로 복구되지 않으므로 관련 오류 로그를 운영 알림 대상으로 관리해야 한다.

## Producer 제한 시간

| 설정 | 기본값 | 제한 대상 |
| --- | ---: | --- |
| `mopl.kafka.producer.max-block-ms` | 3000ms | Batch `KafkaTemplate.send()` 내부의 메타데이터 조회 및 Producer 버퍼 확보 대기 |
| `mopl.kafka.producer.request-timeout-ms` | 5000ms | Batch Producer의 브로커 요청 응답 대기 |
| `mopl.kafka.producer.delivery-timeout-ms` | 10000ms | Batch Producer가 한 레코드의 전송을 완료할 수 있는 전체 시간 |
| `batch.content-search-sync.kafka-completion-timeout-ms` | 15000ms | DB 커밋 후 Batch가 모든 전송 Future의 완료 결과를 수집하는 전체 대기 시간 |

Batch Producer는 `KafkaTemplate.send()` 호출 자체를 가상 스레드에서 제출한다.
따라서 브로커 메타데이터 조회 지연이 콘텐츠 수만큼 Batch 호출 스레드에
순차 누적되지 않는다. 완료 대기 시간은 이벤트별 시간이 아니라 한 번의 전체
발행 묶음에 적용된다.

위 Producer 제한 시간은 Batch 전용 Producer에만 적용되며 API와 Realtime의 공통
Producer 설정은 변경하지 않는다. 완료 대기 시간은 메타데이터 최대 대기 시간과
전송 완료 제한 시간의 합보다 길어야 한다. 전체 대기 시간이 초과되면 아직
`send()` 호출 중인 작업에는 중단을 요청한다. Kafka Producer에 이미 접수된 레코드는
취소 시점에 따라 제한 시간 로그 이후 ACK될 수 있다.

`delivery-timeout-ms`는 `request-timeout-ms + linger.ms` 이상이어야 한다.
현재 `linger.ms`는 0으로 설정되며, 조건을 위반하면 Batch Producer Bean 초기화
단계에서 실패한다.

## 기존 공통 Consumer 재시도 설정

아래 공통 설정은 기존 공통 ErrorHandler를 사용하는 Listener에 적용한다.
콘텐츠 일반 색인과 Embedding은 전용 ErrorHandler를 사용하며, 각각의 별도 설정이
없으면 아래 retry 간격·횟수를 fallback 값으로 사용한다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `mopl.kafka.consumer.retry-interval-ms` | 1000ms | 재시도 간격 |
| `mopl.kafka.consumer.retry-max-retries` | 3회 | 최초 처리 1회 이후 추가 재시도 횟수(총 4회 처리) |

기존 `mopl.kafka.consumer.retry-max-attempts` 설정도 호환을 위해 계속 지원하며,
두 설정이 모두 있으면 `retry-max-retries`를 우선 적용한다.

Issue #15 PR2 이후 콘텐츠 일반 색인과 Embedding은 각각 전용 ErrorHandler를 사용한다.
명확한 콘텐츠 레코드 오류는 재시도 없이 DLT로 이동하고, 일반 원인 불명 오류는
기본 1초 간격 3회 추가 재시도 후 DLT로 이동한다. Elasticsearch/OpenAI 연결·서비스
장애는 재시도 후 해당 Consumer 파티션을 중지하고 Offset을 유지한다. 인증·권한·설정
장애와 원인 불명 ES 400/OpenAI 429는 불필요한 재시도 없이 파티션을 중지한다.
다른 Listener와 Batch는 기존 공통 ErrorHandler를 계속 사용한다.

DLT 브로커 ACK를 확인한 뒤에만 원본 Offset이 진행된다. DLT 발행 예외·실패·timeout이면
기존 `KafkaListenerFailureRecoverer`로 해당 파티션을 중지하고 원본 Offset을 보존한다.
timeout 직후 늦은 브로커 ACK가 도착하면 재개 후 같은 DLT 레코드가 중복될 수 있으므로
중복을 허용하고 DLT와 원본의 Key·offset을 대조한다.

공통 장애 파티션은 PR2에서 자동 재개하지 않는다. PR2는 runtime resume용 관리 API/JMX를
추가하지 않으므로 기본 운영 절차는 장애 원인을 고친 뒤 원본 consumer group offset과 lag를
기록하고 API 인스턴스를 재시작하는 것이다. 내부 관리 채널에서
`KafkaListenerEndpointRegistry`를 이미 제어할 수 있는 경우에만 대상 Listener ID와
TopicPartition을 확인해 파티션을 명시적으로 재개한다. 재개 뒤 같은 레코드가 재전달될 수
있으므로 최신 DB 상태와 Elasticsearch 상태를 확인한다. 장기 중지 시 원본 Topic retention을
넘기지 않도록 확인한다.

## 콘텐츠 DLT Topic

| 설정 | 기본값 | 용도 |
| --- | ---: | --- |
| Topic | `content-search-sync-dlt`, `content-embedding-dlt` | 일반 색인·Embedding 별도 실패 보관 |
| `mopl.kafka.dlt.partitions` | 1 | 각 DLT 파티션 수. 원본 partition 번호로 발행하므로 원본보다 작게 둘 수 없음 |
| `mopl.kafka.dlt.replicas` | 1 | 로컬 기본값. 운영 브로커 수와 내구성 요구에 맞게 설정 |
| `mopl.kafka.dlt.retention-ms` | 2592000000 (30일) | DLT 보존 기간 |
| `mopl.kafka.dlt.producer.max-block-ms` | 3000ms | Producer 메타데이터·버퍼 대기 제한 |
| `mopl.kafka.dlt.producer.request-timeout-ms` | 5000ms | 브로커 요청 대기 제한 |
| `mopl.kafka.dlt.producer.delivery-timeout-ms` | 10000ms | Producer 전송 완료 제한 |
| `mopl.kafka.dlt.producer.ack-timeout-ms` | 15000ms | Recoverer의 전송 결과 확인 대기 |

Producer는 `acks=all`, idempotence를 사용한다. 설정 검증은
`delivery-timeout-ms >= request-timeout-ms`와
`ack-timeout-ms >= delivery-timeout-ms`를 요구한다. `max-block-ms`는 `send()` Future가
반환되기 전 메타데이터 대기이고 ACK 제한은 Future 완료 대기이므로 전체 최대 대기에는
두 구간이 합산될 수 있다. DLT 파티션 번호를 보존하므로 DLT 파티션 수는 해당 원본 Topic의
파티션 수 이상이어야 한다. 기존 원본 Topic partition 설정은 자동 변경하지 않는다.

DLT 레코드는 원래 Key·이벤트 Value·Type ID와 Spring Kafka의 원본 Topic/Partition/Offset,
예외 유형·메시지 헤더를 보존한다. 헤더 크기와 불필요한 본문 노출을 줄이기 위해 stack trace는
넣지 않는다. Key 또는 Value 역직렬화 실패는 실패한 원본 `byte[]`를 보관한다. 처리 원인이 해결되지
않은 역직렬화 실패 레코드는 수동 재처리 CLI가 거부한다.

KafkaAdmin은 새 DLT를 기본 설정으로 생성한다. 이미 존재하는 DLT의 partition 수나 retention
설정은 기존 Topic 설정에 자동으로 덮어쓰지 않으므로 운영자가 배포 전에 Broker 설정을 확인한다.

일반 색인과 Embedding의 추가 재시도 기본값은 각각 1초 간격 3회다. 개별 설정은
`mopl.kafka.content-search-sync.retry-interval-ms`,
`mopl.kafka.content-search-sync.retry-max-retries`,
`mopl.kafka.embedding.consumer.retry-interval-ms`,
`mopl.kafka.embedding.consumer.retry-max-retries`로 조정할 수 있다. 공통 장애 자동 판정과
자동 재개는 PR3 범위이며 PR2에는 수동 재개만 있다.

## 콘텐츠 DLT 선별 재처리

`reindex-cli`의 `DLT_REPLAY`는 운영자가 지정한 DLT Topic·Partition·Offset 하나만 읽고,
원본 Topic에 새 이벤트를 발행한다. DLT consumer offset을 commit하지 않으며 원본 발행 ACK를
확인한다. 같은 명령을 다시 실행하면 중복 발행될 수 있다.

일반 색인은 현재 MySQL에서 삭제 여부를 다시 계산해 `content-search-sync`로 발행한다.
Embedding은 삭제/부재 콘텐츠이면 건너뛰고 활성 콘텐츠이면 `content-embedding`으로 발행한다.
기존 Consumer가 최신 DB 상태, Hash, 모델 및 문서 상태를 검사한다. DLT는 자동 소비되지 않는다.
먼저 장애 원인과 선택 레코드를 확인하고, 원본 group offset·DLT offset을 기록한 뒤 한 건씩
재처리하고 Elasticsearch 결과와 로그를 확인한다. 운영 명령과 raw-byte 제한은 [CLI 안내](./content-search-reindex-cli.md)에 있다.

## 외부 콘텐츠 ID

신규 수집 데이터의 `external_id`가 `null` 또는 공백이면 Upsert 전에 실패 처리한다.
기존 DB에 저장된 `external_id IS NULL` 데이터는 이번 변경에서 수정하거나 삭제하지
않는다. MySQL은 nullable 유니크 키에서 여러 `NULL`을 허용하고 `NULL` 동등 비교도
성립하지 않으므로, 식별자 없는 신규 데이터를 허용하면 안정적인 UUID 재조회가
불가능하다.

외부 ID 대소문자 비교는 DB 컬럼의 collation을 따른다. UUID 조회 시 DB에 저장된
문자열 대신 이번 요청의 식별자를 결과에 연결하여, 대소문자를 구분하지 않는
collation에서도 기존 UUID를 정확히 재사용한다.

## Batch 실행 방법

콘텐츠 수집 Batch는 API 서버와 분리하여 실행한다. `contentCollectionJob`은 TMDB Step과 SportsDB Step을 순서대로 실행한다.

실행 전 MySQL, Kafka 연결 설정과 외부 API 인증 정보를 확인한다.

- DB 접속 환경변수: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- Kafka 접속 환경변수: `KAFKA_BOOTSTRAP_SERVERS` (로컬 기본값: `localhost:9092`)
- 외부 API 인증 정보: `TMDB_ACCESS_TOKEN`, `SPORTSDB_API_KEY`
- 필수 Job Parameter: `collectionDate` (`yyyy-MM-dd` 형식)

로컬 실행 예시:

```bash
./gradlew :batch:bootRun --args='--spring.profiles.active=local collectionDate=2026-10-11'
```

`collectionDate`는 필수 Job Parameter이며 `yyyy-MM-dd` 형식이 아니면 Validator에서 실행을 거부한다.

현재 Batch에는 실행 대상 Job이 `contentCollectionJob` 하나이므로 Spring Boot가 자동으로 실행한다.

Batch는 `web-application-type: none`으로 구성되어 Job 완료 후 프로세스가 종료된다. `JobExecutionExitCodeGenerator`를 통해 실행 결과에 따른 종료 코드가 반환되므로 로그와 종료 코드를 함께 확인한다.

실제 실행 전에 환경변수와 외부 API 인증 정보를 설정해야 하며, 위 실행 명령은 로컬 환경에서 동작을 확인할 필요가 있다.

## Kafka 이벤트 유실 시 복구

현재 구조에는 Transactional Outbox가 없어 DB 커밋 후 Kafka 이벤트 발행에 실패하면 Elasticsearch 검색 데이터가 MySQL과 달라질 수 있다.

이벤트 유실이 의심되는 경우 다음 순서로 복구한다.

1. Kafka 및 Elasticsearch 장애 원인을 확인하고 복구한다.
2. Batch 콘텐츠 수집 작업과 검색 동기화 작업의 실행 상태를 확인한다.
3. 운영 영향이 적은 유지보수 구간을 확보한다.
4. `reindex-cli`의 `SYNC_DATA` 모드를 실행하여 MySQL 기준으로 Elasticsearch를 다시 동기화한다.
5. 종료 코드 `0`을 확인하고 주요 콘텐츠의 검색 결과를 검증한다.

`SYNC_DATA`는 기존 Embedding을 보존하며, 동기화 과정에서 발생한 예외는 종료 코드 `3`으로 반환된다.

자세한 실행 방법과 종료 코드별 대응은 [검색 데이터 재색인 CLI 운영 안내](./content-search-reindex-cli.md)를 참고한다.

## Issue #15 PR1 — Embedding Consumer 분리

일반 Consumer는 최신 DB 콘텐츠의 Elasticsearch 색인에 성공한 뒤
`content-embedding`에 `ContentEmbeddingKafkaEvent(contentId)`를 발행한다.
Key는 `contentId` 문자열이며 논리 Type ID는 `contentEmbedding`이다.
삭제·부재 콘텐츠에는 발행하지 않는다. 기존 `content-search-sync` 계약과 그룹은 유지한다.

일반 Listener는 Embedding 이벤트 발행 ACK를 확인한 뒤 정상 반환한다.
동기 전송 오류, Future 실패, ACK timeout, 인터럽트는 원본 처리 실패로 전파한다.
인터럽트 상태는 복원한다. ACK 후 원본 Offset 커밋 전에 종료되거나 timeout 뒤
늦은 ACK가 도착하면 요청이 중복될 수 있다. ES와 Kafka 사이의 exactly-once는 보장하지 않는다.

전용 Consumer는 최신 활성 콘텐츠를 DB에서 읽고 `ContentEmbeddingIndexer.indexForKafka()`를
호출한다. 기존 Hash·모델 검사와 조건부 벡터 업데이트(no upsert)를 재사용한다.
AI 호출·ES 조회/저장 실패는 Kafka 오류 처리로 전파하며, 기존 `index()`의 best-effort와
Backfill의 실패 집계·중단 정책은 유지한다. 최신 벡터, Hash 불일치, 문서 부재/삭제 등
기존 생략 조건도 유지하므로 필요 시 `SYNC_DATA`와 `EMBEDDING_BACKFILL`로 보완한다.

### PR1 과도기 오류 정책

두 Listener는 기존 `kafkaListenerContainerFactory`와 공통 `DefaultErrorHandler`를 사용한다.
기본 재시도는 1000ms 간격 추가 3회이며, 재시도 가능한 오류가 소진되면 해당 파티션을
중지하고 Offset을 보존한다. Embedding 파티션이 중지되어도 일반 Topic의 소비는 계속된다.
일반 색인 후 Kafka 발행 자체가 실패하면 일반 Topic 쪽 재시도·중지가 발생한다.

PR1에는 DLT·오류 분류(PR2), 자동 재개(PR3), 통계 정합성 변경(#14)이 없다.
장애를 해결한 후 기존처럼 파티션을 수동 재개하거나 서버를 재시작한다.
장기 중지 시 원본 Topic retention을 넘기지 않도록 확인한다.

### 설정

아래 값은 코드의 기본값이며 Spring 외부 설정으로 변경할 수 있다.
Embedding Producer만 별도 Factory/Template을 사용하므로 기존 API·Batch Producer의
timeout은 변경되지 않는다. 일반 Consumer의 poll 설정도 변경하지 않는다.

| 설정 | 기본값 | 용도 |
| --- | --- | --- |
| `mopl.kafka.embedding.partitions` | 1 | 신규 `content-embedding` Topic 파티션 수 |
| `mopl.kafka.embedding.replicas` | 1 | 신규 Topic 복제 수. 운영 브로커 구성에 맞춰 설정 |
| `mopl.kafka.embedding.group-id` | `mopl-content-embedding-group` | Embedding 전용 그룹 |
| `mopl.kafka.embedding.concurrency` | 1 | Embedding Listener 동시성 |
| `mopl.kafka.embedding.max-poll-records` | 1 | Embedding Consumer의 poll당 최대 레코드 수 |
| `mopl.kafka.embedding.max-poll-interval-ms` | 300000 | Embedding Consumer poll 간 최대 간격 |
| `mopl.kafka.embedding.producer.max-block-ms` | 3000 | `send()` 내부 메타데이터·버퍼 대기 제한 |
| `mopl.kafka.embedding.producer.request-timeout-ms` | 5000 | 브로커 요청 응답 대기 |
| `mopl.kafka.embedding.producer.delivery-timeout-ms` | 10000 | Producer 전송 완료 제한 |
| `mopl.kafka.embedding.producer.ack-timeout-ms` | 15000 | `send()` 반환 후 Future 완료 대기 |

Producer의 `linger.ms`는 0이며 `delivery.timeout.ms >= request.timeout.ms`여야 한다.
ACK timeout은 `send()` 자체의 대기를 포함하지 않는다. 기본 설정에서 두 대기 구간은
각각 최대 3초와 15초다. ACK timeout 후 Future를 취소해도 브로커의 늦은 기록을
취소할 수 있다고 가정하지 않으며, 원본 재처리로 인한 중복을 허용한다.

Embedding Listener는 `auto.offset.reset=earliest`를 명시한다. 기존 committed offset이
있으면 그 위치에서 계속하며, 신규 그룹은 시작 전 쌓인 요청도 처리한다.
Spring Kafka 3.3.16의 `@KafkaListener(properties=...)`가 Factory의 Consumer 속성을
Listener별로 덮어쓰는 기능을 사용한다. `max.poll.records`도 이 경로로 설정하며
공통 ConsumerFactory의 설정은 변경하지 않는다.
[공식 문서](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/listener-annotation.html#annotation-properties)

기존 일반 Topic의 파티션 수는 이 변경으로 조정하지 않는다. 신규 Topic은 KafkaAdmin이
생성·관리하므로 필요한 권한을 확인하고, 기존 Topic의 파티션 설정 변경은 별도 운영 작업으로
판단한다. 병렬도를 늘릴 때는 Topic 파티션 수, 인스턴스 수, OpenAI 제한을 함께 고려한다.

### AI 비활성 및 다시 활성화할 때

`AiAvailability`가 비활성 또는 API Key 누락을 판단하면 일반 색인은 계속하지만
Embedding 이벤트는 발행하지 않는다. 전용 Listener ID는 `content-embedding-consumer`이며,
Bean은 등록되어도 `autoStartup=false`여서 backlog를 소비하지 않는다.
비활성 Listener를 강제로 시작하더라도 메시지를 no-op ACK하지 않고 오류를 전파한다.

`autoStartup`은 기동 시 평가되며 환경변수 변경을 자동 감지하지 않는다.
현재 `AiAvailability` 설정도 기동 시 주입되므로 AI 활성 플래그·Key를 변경했다면
설정을 적용하여 서버를 재시작하는 것이 기본 절차다. 이미 AI 사용 가능한 설정으로
실행 중이고 운영자가 Listener만 정지한 경우에는 `KafkaListenerEndpointRegistry`에서
해당 ID의 컨테이너를 명시적으로 시작할 수 있다. 이 PR은 시작 API나 자동 재개 기능을
추가하지 않는다. 파티션 pause 상태라면 컨테이너 시작과 별개로 해당 파티션 재개가 필요하다.

재활성화 후 기존 backlog는 전용 그룹의 Offset부터 처리한다. 비활성 기간에는 요청을
발행하지 않았으므로 backlog 처리만으로 그 기간의 Embedding이 모두 복구되지는 않는다.
일반 검색 문서의 동기화 상태를 확인한 뒤 기존 CLI의 `EMBEDDING_BACKFILL`을 실행한다.
일반 색인 유실·Hash 불일치가 있으면 먼저 `SYNC_DATA`, 다음으로 Backfill을 실행한다.
기존 CLI 운영 안내의 writer 동시 실행·모델 변경 제한을 지키고 결과의 실패·건너뜀·미처리
건수를 확인한다. 실제 OpenAI를 호출하는 Backfill은 테스트 실행과 별개의 운영 작업이다.
