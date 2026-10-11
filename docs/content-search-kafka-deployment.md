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

현재 범위에는 Outbox와 DLT가 없다. DB 커밋 후 Kafka 발행이 실패한 이벤트를
자동으로 복원하지 않으므로 관련 오류 로그를 운영 알림 대상으로 관리해야 한다.

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

## Consumer 일반 처리 오류

일반 Listener 처리 오류는 다음 설정에 따라 재시도한다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `mopl.kafka.consumer.retry-interval-ms` | 1000ms | 재시도 간격 |
| `mopl.kafka.consumer.retry-max-retries` | 3회 | 최초 처리 1회 이후 추가 재시도 횟수(총 4회 처리) |

기존 `mopl.kafka.consumer.retry-max-attempts` 설정도 호환을 위해 계속 지원하며,
두 설정이 모두 있으면 `retry-max-retries`를 우선 적용한다.

재시도를 모두 소진하면 Listener 컨테이너에 실패 파티션 중지를 요청하고 해당
Offset을 유지한다. 컨테이너가 중지 요청을 관리하므로 Consumer 재할당 후에도
파티션을 다시 중지한다. Elasticsearch 장애가 해소된 뒤에는 운영자가 컨테이너의
해당 파티션을 명시적으로 재개하거나 API 인스턴스를 재시작해야 한다. 역직렬화
실패는 기존 정책대로 복구 로그를 남기고 Offset을 진행한다.

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

콘텐츠 수집 Batch는 API 서버와 분리하여 실행합니다.

실행 전 MySQL 및 Kafka 연결 설정을 확인하고, 콘텐츠 수집 Job을 실행합니다.

구체적인 실행 명령과 Job 파라미터는 실제 Batch Job 구성을 확인한 후 기재합니다.

## Kafka 이벤트 유실 시 복구

현재 구조에는 Transactional Outbox가 없어 DB 커밋 후 Kafka 이벤트 발행에 실패하면 Elasticsearch 검색 데이터가 MySQL과 달라질 수 있습니다.

이벤트 유실이 의심되는 경우 다음 순서로 복구합니다.

1. Kafka 및 Elasticsearch 장애 원인을 확인하고 복구합니다.
2. Batch 콘텐츠 수집 작업과 검색 동기화 작업이 실행 중인지 확인합니다.
3. 운영 영향이 적은 유지보수 구간을 확보합니다.
4. `reindex-cli`의 `SYNC_DATA` 모드를 실행하여 MySQL 기준으로 Elasticsearch를 다시 동기화합니다.
5. 종료 코드가 `0`인지 확인하고 주요 콘텐츠의 검색 결과를 검증합니다.

`SYNC_DATA`는 기존 Embedding을 보존하며, 동기화 과정에서 발생한 예외는 종료 코드 `3`으로 반환됩니다.

자세한 실행 방법과 종료 코드별 대응은 [검색 데이터 재색인 CLI 운영 안내](./content-search-reindex-cli.md)를 참고하세요.
