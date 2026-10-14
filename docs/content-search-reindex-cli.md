# 검색 데이터 재색인 CLI 운영 안내

`reindex-cli`는 API 서버와 분리된 일회성 실행 도구입니다. 실행 한 번에 모드를 하나만 선택합니다. API의 웹 서버, Kafka Listener, 스케줄러와 관리자 초기화 작업은 시작하지 않습니다.

## 사전 준비

- Java 21과 저장소의 Gradle Wrapper를 사용합니다.
- MySQL과 Elasticsearch가 실행 중이어야 합니다. 접속 정보는 `.env.example`을 참고해 PowerShell 프로세스 환경 변수로 설정합니다. `.env.example` 자체가 자동으로 로드되지는 않습니다.
- 별도 Spring 프로필은 필요하지 않습니다. `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `ELASTICSEARCH_URL`을 환경에 맞게 설정하세요. CLI 초기화 코드가 이 값을 각각 datasource와 Elasticsearch 설정으로 변환합니다.
- 재색인 CLI는 Flyway 마이그레이션을 실행하지 않습니다. 배포된 API 또는 별도 마이그레이션 절차로 DB 스키마를 먼저 최신 상태로 맞추세요. CLI의 Hibernate 스키마 동작은 `validate` 전용이며 테이블을 생성하거나 변경하지 않습니다.

## 기존 `contents` 인덱스 매핑 및 최초 Backfill 비용

새 애플리케이션을 배포하기 전에 실제 검색 인덱스 또는 alias가 가리키는 인덱스와 현재 매핑을 확인합니다. 기존 인덱스에는 아래 세 필드를 `keyword`로 먼저 추가해야 합니다.

```http
GET /contents/_mapping

PUT /contents/_mapping
{
  "properties": {
    "contentInputHash": { "type": "keyword" },
    "embeddingInputHash": { "type": "keyword" },
    "embeddingModel": { "type": "keyword" }
  }
}

GET /contents/_mapping
```

실제 이름이 `contents`와 다르거나 alias를 사용하는 환경에서는 실제 대상 인덱스를 확인해 요청을 조정합니다. 필드가 이미 존재하면 타입이 `keyword`인지 확인하세요. 신버전 색인이 매핑 적용보다 먼저 시작되면 Elasticsearch dynamic mapping이 문자열 필드를 다른 타입으로 생성해 이후 `keyword` 매핑 추가와 충돌할 수 있습니다. 타입 충돌이 있다면 기존 필드 매핑을 강제로 변경하지 말고 별도 인덱스를 생성해 재색인한 뒤 alias를 전환하는 절차를 사용합니다. 매핑 변경은 기존 문서의 hash나 모델 메타데이터를 채우지 않으므로 이후 `SYNC_DATA`와 Backfill이 필요합니다.

Backfill은 `embedding` 누락뿐 아니라 `embeddingInputHash` 또는 `embeddingModel`이 없거나 현재 입력 hash·설정 모델과 불일치하는 문서도 재생성 대상으로 찾습니다. 따라서 기존 문서에 embedding 벡터가 있어도 생성 메타데이터가 없는 legacy 문서는 다시 OpenAI API를 호출할 수 있습니다. 기존 embedding이 메타데이터 없이 저장된 환경에서는 최초 Backfill이 대량의 OpenAI 요청과 비용을 발생시킬 수 있습니다. 최초 실행 전에 실제 콘텐츠 건수, 사용할 모델, 예상 토큰 사용량을 기준으로 요청량과 비용을 사전 산정하세요. 실제 비용은 데이터와 모델, 실제 토큰 사용량에 따라 달라지므로 확인되지 않은 금액을 전제하지 않습니다.

## 운영 실행 정책

재색인 CLI는 운영자가 실행 시점을 명시적으로 결정하는 일회성 도구입니다.

- 서비스 영향을 최소화하기 위해 트래픽이 적은 유지보수 구간에 실행하는 것을 권장합니다.
- 동일한 Elasticsearch 인덱스를 대상으로 하는 `SYNC_DATA`와 `EMBEDDING_BACKFILL`을 동시에 실행하지 않습니다.
- 콘텐츠 수집 Batch와 실행 시간이 겹치지 않도록 조정하고, Kafka Consumer의 검색 인덱스 갱신과 충돌하지 않도록 운영 구간을 관리합니다.
- #12 최초 배포와 모델 변경 배포 모두 구버전 writer가 남지 않도록 합니다. 모든 API pod을 #12 이후 코드로 교체하고 구버전 Kafka consumer 및 embedding writer와 진행 중인 작업이 모두 종료된 것을 확인한 뒤 동기화 작업을 실행합니다. 구버전 색인·embedding writer는 신규 메타데이터를 지우거나 지연된 이전 embedding으로 최신 결과를 덮어쓸 수 있습니다. `SYNC_DATA`와 `EMBEDDING_BACKFILL` 모두 #12 이후 코드로 빌드한 CLI JAR를 사용해야 합니다. 특히 구버전 CLI JAR의 `SYNC_DATA`는 신규 embedding 메타데이터를 지울 위험이 있습니다. 실행 순서는 `SYNC_DATA` 완료 후 `EMBEDDING_BACKFILL`입니다. 모델 교체 때도 구·신 모델 writer를 동시에 실행하지 마세요.
- 실행 전 MySQL과 Elasticsearch의 연결 상태 및 DB 스키마를 확인합니다.
- 실행 후 종료 코드와 로그를 확인하고 주요 콘텐츠의 검색 결과를 검증합니다.

현재 CLI에는 중복 실행을 자동으로 차단하는 분산 락이 없으므로, 동시 실행 방지는 운영 절차로 관리합니다.

- `SYNC_DATA` 중복 실행: 동일 데이터의 반복 동기화로 MySQL 및 Elasticsearch 부하가 증가할 수 있습니다.
- `EMBEDDING_BACKFILL` 중복 실행: 동일 문서에 대해 OpenAI Embedding API가 중복 호출되어 불필요한 비용이 발생할 수 있습니다.

따라서 동일 모드의 중복 실행과 서로 다른 재색인 모드의 동시 실행을 모두 금지합니다.

## SYNC_DATA 실행

MySQL 콘텐츠를 기존 검색 동기화 로직으로 Elasticsearch에 반영합니다. Embedding Backfill은 실행하지 않습니다.
이 모드에는 `MOPL_AI_ENABLED`나 `OPENAI_API_KEY`가 필요하지 않습니다.

Kafka 이벤트 유실로 검색 데이터 불일치가 발생한 경우에는 [콘텐츠 검색 Kafka 동기화 배포 및 운영 가이드](./content-search-kafka-deployment.md)의 복구 절차를 참고해 `SYNC_DATA`를 실행합니다.

```powershell
.\gradlew.bat :reindex-cli:bootRun --args="--mopl.reindex.mode=SYNC_DATA"
```

## EMBEDDING_BACKFILL 실행

AI 기능과 OpenAI API Key를 모두 설정해야 합니다. API Key는 저장소나 셸 기록에 남기지 말고 안전한 비밀 저장소를 통해 환경 변수로 전달하세요.

```powershell
$env:MOPL_AI_ENABLED = "true"
# 안전한 비밀 저장소에서 OPENAI_API_KEY를 현재 프로세스 환경에 설정
$env:MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEFAILURES = "5"
$env:MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEESFAILURES = "5"
.\gradlew.bat :reindex-cli:bootRun --args="--mopl.reindex.mode=EMBEDDING_BACKFILL"
```

두 임계치는 각각 OpenAI Embedding 생성 실패와 Elasticsearch 저장 실패의 연속 허용 횟수입니다. 기본값은 각각 5이며 양수만 허용합니다. 환경 변수 이름은 Spring Boot의 relaxed binding 규칙에 맞춰 프로퍼티 이름의 하이픈을 제거한 형태입니다.

| 환경 변수 | 실제 Spring 프로퍼티 |
| --- | --- |
| `MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEFAILURES` | `mopl.elasticsearch.embedding-backfill.max-consecutive-failures` |
| `MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEESFAILURES` | `mopl.elasticsearch.embedding-backfill.max-consecutive-es-failures` |

`bootRun`은 빠른 실행 확인에는 사용할 수 있지만, `$LASTEXITCODE`에는 CLI 자체의 종료 코드가 아니라 Gradle Wrapper의 종료 상태가 남습니다. CLI가 `0`이면 Gradle도 성공(`0`)합니다. CLI가 `1`, `2`, `3` 중 하나를 반환하면 `bootRun` 작업이 실패하고 Gradle Wrapper는 `1`로 종료하므로, 이 방식으로는 CLI의 세 오류 코드를 구분할 수 없습니다. 운영 확인에서는 `bootJar`로 만든 JAR를 직접 실행합니다.

현재 `bootJar` 산출물은 `reindex-cli-0.0.1-SNAPSHOT.jar`이지만, JAR 이름에는 Gradle 프로젝트 버전이 포함되므로 실행 명령에 특정 버전 문자열을 고정하지 않습니다. 아래 예시는 실행 가능한 JAR 후보가 정확히 하나인지 확인해 오래된 JAR를 실수로 실행하지 않도록 합니다.

```powershell
.\gradlew.bat :reindex-cli:bootJar
$jars = @(Get-ChildItem .\reindex-cli\build\libs\reindex-cli-*.jar -File |
    Where-Object { $_.Name -notlike "*-plain.jar" })
if ($jars.Count -ne 1) {
    throw "실행 가능한 reindex-cli JAR 후보가 $($jars.Count)개입니다. 이번 bootJar 출력 JAR 하나만 지정하세요."
}

java -jar $jars[0].FullName --mopl.reindex.mode=SYNC_DATA
$cliExitCode = $LASTEXITCODE
Write-Host "CLI exit code: $cliExitCode"
```

Embedding 모드는 같은 JAR 명령에서 모드만 바꾸고 `MOPL_AI_ENABLED=true`, `OPENAI_API_KEY` 및 필요 시 실패 임계치 환경 변수를 제공하면 됩니다.

## 종료 코드와 대응

| 코드 | 의미 | 대응 |
| --- | --- | --- |
| `0` | 선택한 작업이 실패 또는 미처리 없이 끝남 | Backfill의 `skipped`에는 `STALE_REJECTED`뿐 아니라 이미 최신인 문서, Elasticsearch 문서 없음, MySQL에서 찾을 수 없거나 삭제된 콘텐츠 등이 포함됩니다. `STALE_REJECTED`는 OpenAI 호출 전 hash 확인 단계와 생성 후 조건부 저장 단계 모두에서 발생할 수 있습니다. 따라서 종료 코드 `0`만으로 전체 문서의 embedding 정합성을 단정하지 말고 실행 요약과 실제 stale 로그를 확인하세요. |
| `1` | Embedding Backfill에서 한 건 이상 실패·미처리가 발생했거나 조기 중단됨 | 실행 로그의 성공·실패·건너뜀·미처리 수와 중단 사유를 확인합니다. OpenAI 401/403이면 Key와 권한을 수정하고, 연속 실패 중단이면 장애 원인을 해결한 뒤 다시 실행합니다. |
| `2` | 모드 오류, 필수 DB·Elasticsearch 설정 누락, Backfill의 AI 비활성화·API Key 누락 또는 실패 임계치의 0 이하 값처럼 CLI가 명시적으로 검사한 설정 오류 | 모드를 `SYNC_DATA` 또는 `EMBEDDING_BACKFILL`로 지정합니다. 접속 설정을 확인하고, Backfill이면 AI 활성화와 API Key 및 두 실패 임계치가 양수인지 확인합니다. |
| `3` | 위 설정 오류 이외의 기동 실패, DB 연결·Hibernate 스키마 검증 실패 또는 작업 전체로 전파된 DB·Elasticsearch 처리 예외 | 환경 변수의 값 형식, DB 스키마와 인프라 상태 및 원인 예외를 확인하고 복구한 뒤 같은 모드로 재실행합니다. |

`SYNC_DATA` 처리 중 발생한 예외는 종료 코드 `3`입니다. Backfill에서는 개별 Embedding 생성 실패나 개별 Elasticsearch 업데이트 실패를 집계해 종료 코드 `1`로 반환하지만, Elasticsearch Scroll 조회, MySQL 배치 조회 또는 마지막 Refresh처럼 작업 전체로 전파된 예외는 종료 코드 `3`입니다.

종료 코드 `0`/`1`/`2`/`3`은 위와 같이 JAR를 직접 실행한 직후의 `$LASTEXITCODE`로 확인합니다. 이후 다른 외부 명령을 실행하면 값이 바뀔 수 있으므로 바로 별도 변수에 보관하세요. 필요하면 호출 스크립트도 같은 코드로 종료합니다.

```powershell
exit $cliExitCode
```

## Linux/macOS Bash 실행

저장소 루트에서 접속 환경 변수를 현재 Bash 프로세스에 설정합니다. 비밀번호와 API Key 같은 비밀값을 명령줄에 직접 작성하면 셸 기록에 남을 수 있으므로, 아래처럼 입력을 숨겨 받거나 조직의 비밀 저장소에서 주입하세요. `.env` 파일은 자동으로 로드되지 않습니다.
macOS 기본 셸인 zsh에서는 일부 Bash 전용 명령어(shopt, read -rsp 등)가 동작하지 않습니다. macOS 사용자는 터미널에서 bash를 먼저 실행해 Bash 셸로 전환한 뒤 아래 명령어를 실행하세요.

```bash
export DB_HOST='127.0.0.1'
export DB_PORT='3306'
export DB_NAME='mopl'
export DB_USERNAME='mopl'
read -rsp 'DB_PASSWORD: ' DB_PASSWORD
export DB_PASSWORD
printf '\n'
export ELASTICSEARCH_URL='http://127.0.0.1:9200'
```

실행 가능한 JAR를 빌드한 뒤 버전 문자열을 고정하지 않고 후보를 찾습니다. `*-plain.jar`는 실행 대상에서 제외하며, 이전 빌드 산출물까지 남아 후보가 둘 이상이면 임의로 선택하지 않고 중단합니다.

```bash
./gradlew :reindex-cli:bootJar

shopt -s nullglob
jar_candidates=()
for jar in reindex-cli/build/libs/reindex-cli-*.jar; do
    [[ "$jar" == *-plain.jar ]] || jar_candidates+=("$jar")
done

if (( ${#jar_candidates[@]} == 1 )); then
    cli_jar=${jar_candidates[0]}
    printf '실행할 JAR: %s\n' "$cli_jar"
else
    unset cli_jar
    printf '실행 가능한 reindex-cli JAR 후보가 %d개입니다. 이번 bootJar 출력 JAR 하나만 지정하세요.\n' \
        "${#jar_candidates[@]}" >&2
fi
```

### SYNC_DATA

`SYNC_DATA`에는 AI 활성화나 OpenAI API Key가 필요하지 않습니다.

```bash
if [[ -z ${cli_jar:-} ]]; then
    printf '실행 가능한 JAR가 없어 SYNC_DATA를 시작하지 않습니다.\n' >&2
    cli_exit_code=1
elif java -jar "$cli_jar" --mopl.reindex.mode=SYNC_DATA; then
    cli_exit_code=0
else
    cli_exit_code=$?
fi
printf 'CLI exit code: %d\n' "$cli_exit_code"
```

### EMBEDDING_BACKFILL

Backfill에는 `MOPL_AI_ENABLED=true`와 OpenAI API Key가 모두 필요합니다. Key를 문서, 명령줄 또는 셸 기록에 직접 입력하지 말고 비밀 저장소에서 주입하거나 아래처럼 기록에 남지 않는 숨김 입력을 사용하세요.

```bash
if [[ -z ${cli_jar:-} ]]; then
    printf '실행 가능한 JAR가 없어 EMBEDDING_BACKFILL을 시작하지 않습니다.\n' >&2
    cli_exit_code=1
else
    # 서브셸에서만 AI 설정을 변경해 실행 후 부모 셸의 환경변수가 유지되도록 한다.
    if (
        export MOPL_AI_ENABLED='true'
        read -rsp 'OPENAI_API_KEY: ' OPENAI_API_KEY || exit 2
        export OPENAI_API_KEY
        printf '\n'

        export MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEFAILURES='5'
        export MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEESFAILURES='5'

        java -jar "$cli_jar" --mopl.reindex.mode=EMBEDDING_BACKFILL
    ); then
        cli_exit_code=0
    else
        cli_exit_code=$?
    fi
fi

printf 'CLI exit code: %d\n' "$cli_exit_code"
```

`if` 조건에서 서브셸을 실행하므로 호출 스크립트에 `set -e`가 설정되어 있어도 CLI 종료 코드를 캡처할 수 있습니다. 서브셸에서 설정한 AI 환경변수는 실행 후 부모 셸에 남지 않습니다. 종료 코드는 다른 외부 명령을 실행하기 전에 `$?`에서 `cli_exit_code`로 보관합니다. 호출 스크립트가 CLI와 같은 코드로 종료해야 한다면 마지막에 다음을 실행하세요. 대화형 셸에서 실행하면 현재 셸이 종료됩니다.

```bash
exit "$cli_exit_code"
```

## AI 최초 활성화 절차

AI 검색 기능을 처음 활성화할 때는 다음 순서를 지킵니다. 신버전 색인이 시작되기 전에 기존 인덱스에 세 keyword 필드를 매핑해야 합니다. 매핑 전에 색인이 시작되면 Elasticsearch dynamic mapping이 필드 타입을 먼저 정해 이후 매핑과 충돌할 수 있습니다. 두 CLI 작업 모두 #12 이후 코드로 빌드한 JAR를 사용하세요. 구버전 `SYNC_DATA`는 신규 embedding 메타데이터를 지울 수 있습니다.

1. 실제 인덱스 또는 alias 대상에 `contentInputHash`, `embeddingInputHash`, `embeddingModel`의 `keyword` 매핑을 적용하고 적용 결과를 확인합니다.
2. 모든 API pod을 #12 버전으로 교체합니다. 구버전 Kafka consumer·embedding writer와 진행 중인 작업이 모두 종료된 것을 확인합니다. 구버전 writer는 신규 메타데이터를 지우거나 지연된 이전 embedding으로 최신 결과를 덮어쓸 수 있습니다.
3. MySQL·Elasticsearch 연결 및 DB 스키마를 확인하고 유지보수 구간을 확보한 뒤, #12 이후 코드로 빌드한 CLI JAR로 `SYNC_DATA`를 실행합니다. 일반 검색 문서와 최신 `contentInputHash`가 반영되었는지 확인합니다.
4. 실제 대상 건수, 모델, 예상 토큰 사용량으로 OpenAI 요청량과 비용을 사전 검토합니다. `MOPL_AI_ENABLED=true`와 유효한 `OPENAI_API_KEY`를 CLI에 준비한 뒤 같은 #12 이후 CLI JAR로 `EMBEDDING_BACKFILL`을 실행합니다. 누락뿐 아니라 hash·모델 메타데이터 누락 또는 불일치 문서도 처리합니다. 종료 코드와 성공·실패·건너뜀·미처리 건수를 확인하고, 실패 또는 미처리가 있으면 원인을 해결해 Backfill을 재실행합니다. 종료 코드 `0`만으로 정합성을 단정하지 않습니다.
5. 최초 Backfill 결과와 Semantic Search·추천 동작을 확인한 뒤 API AI를 활성화합니다. 첫 Backfill 완료부터 AI 활성화 전까지 유입된 콘텐츠는 embedding이 없을 수 있으므로, 활성화 후 `EMBEDDING_BACKFILL`을 다시 실행합니다. 입력 hash와 모델이 이미 최신인 embedding은 재생성하지 않습니다.

이후 새로 색인된 문서는 Kafka embedding 경로에서 처리됩니다. 필요하면 동일한 점검 절차로 Backfill을 재실행합니다.

`SYNC_DATA`는 기존 embedding과 생성 metadata를 보존합니다. `EMBEDDING_BACKFILL`은 입력 hash와 모델이 현재 상태인 embedding은 다시 생성하지 않지만, 기존 벡터의 metadata가 없거나 stale이면 재생성합니다.

AI 검색 기능을 사용자에게 공개하는 시점은 Backfill 처리 결과와 검색 동작을 검증한 후 결정합니다.

## Backfill 재실행과 처리 특성

- Backfill은 `embedding`이 없는 문서뿐 아니라 `embeddingInputHash` 또는 `embeddingModel`이 누락되거나 `contentInputHash`와 embedding 입력 hash 또는 현재 모델이 불일치하는 문서도 찾습니다. hash와 모델이 모두 최신인 기존 embedding은 재호출하지 않습니다.
- `contentInputHash`가 없거나 DB 입력 hash와 다르면 OpenAI를 호출하지 않고 `STALE_REJECTED`로 건너뜁니다. 같은 결과는 embedding 생성 중 입력이 바뀌어 조건부 저장이 거부된 경우에도 발생합니다. 실제 WARN 로그 문구는 각각 `contentInputHash가 없어 일반 SYNC_DATA가 필요하므로 embedding Backfill을 건너뜁니다.`, `Elasticsearch contentInputHash가 현재 DB 입력과 달라 embedding Backfill을 건너뜁니다.`, `최신 contentInputHash와 생성 당시 hash가 일치하지 않아 stale embedding 업데이트를 거부했습니다.`입니다. 일반 색인 동기화가 필요한 경우 `SYNC_DATA` 완료 후 Backfill을 다시 확인합니다.
- Backfill 요약의 `skipped`에는 `STALE_REJECTED`, 이미 최신인 embedding, Elasticsearch 문서 없음, MySQL에서 찾을 수 없거나 soft-delete된 콘텐츠뿐 아니라 UUID 형식 오류와 중복 문서 ID도 포함될 수 있습니다. UUID 오류 로그는 `embedding Backfill 검색 결과의 문서 ID가 UUID 형식이 아니어서 건너뜁니다.`, 중복 로그는 `embedding Backfill 검색 결과에 중복 문서 ID가 있어 건너뜁니다.`이며 둘 다 WARN입니다. 이미 최신인 문서의 `embedding이 이미 최신 상태여서 Backfill을 건너뜁니다.`는 DEBUG 로그이므로 기본 INFO 로그 설정에서는 표시되지 않습니다. Elasticsearch 문서 삭제 로그는 `embedding Backfill 처리 전에 Elasticsearch 문서가 삭제되어 건너뜁니다.` 또는 `콘텐츠 Embedding 저장 전에 Elasticsearch 문서가 삭제되어 건너뜁니다.`이며 WARN입니다. DB에서 누락되거나 삭제된 콘텐츠는 batch 조회 결과의 수 차이로 집계되며 개별 로그가 항상 출력되는 것은 아닙니다.
- 검색 Scroll을 닫은 뒤 전체 스캔에서 확인한 고유 UUID를 메모리에 수집하고, 이후 MySQL의 활성 콘텐츠 및 태그를 500개 단위로 조회합니다. 따라서 메모리 사용량은 누락 UUID 수만이 아니라 전체 스캔 문서 ID 수에 영향을 받습니다. 데이터 규모가 크게 늘면 영속 체크포인트 또는 페이지 기반 설계를 별도 검토해야 합니다.
- MySQL에서 찾을 수 없거나 `deletedAt`이 설정된 콘텐츠는 건너뜁니다. Backfill은 일반 검색 문서를 새로 만들지 않습니다.
- OpenAI 인증 오류 401/403은 즉시 중단합니다. 다른 OpenAI 실패와 Elasticsearch 저장 실패는 서로 분리된 연속 실패 임계치를 사용합니다. 해당 임계치에 도달하면 남은 문서는 미처리로 기록되고 종료 코드 `1`이 반환됩니다.
- 일부 성공 후 실패해도 성공한 Embedding은 보존됩니다. 재실행 시 입력 hash와 모델이 모두 최신인 embedding만 재생성을 건너뜁니다. embedding이 없거나 입력 hash 또는 모델이 불일치하는 문서는 다시 처리합니다. 복잡한 재시도 큐나 별도 체크포인트는 사용하지 않습니다.
- 종료 코드 `3`이거나 검색/DB 처리 중 CLI가 예외로 종료됐다면 로그의 원인을 먼저 해결한 뒤 재실행합니다. Scroll 조회 실패는 불완전한 검색 ID 목록으로 처리를 시작하지 않습니다.

## 모델 교체 및 vector 차원 변경

모델명은 실제 embedding 생성 모델을 식별하지만, 서로 다른 버전 writer의 선후 관계를 코드에서 판정하는 epoch/fencing은 구현되어 있지 않습니다. 모델을 교체할 때는 구 설정의 API/Kafka writer와 실행 중인 Backfill을 모두 중지하고, in-flight 작업이 완료된 것을 확인한 뒤 새 설정으로 전환합니다. 입력 콘텐츠가 변경되었거나 Elasticsearch의 `contentInputHash`를 최신화해야 하는 경우 새 설정으로 `SYNC_DATA`를 실행한 뒤 `EMBEDDING_BACKFILL`을 실행하세요. 모델명만 바뀌고 입력 hash가 동일하다면 저장된 `embeddingModel`과 현재 모델의 불일치로 Backfill이 재생성을 판정하므로 `SYNC_DATA`는 항상 필수는 아닙니다. 구·신 설정 writer가 겹치면 늦게 끝난 구 모델 결과가 새 결과를 덮어쓸 수 있습니다.

현재 검색 문서의 인덱스명 `contents`와 dense vector 1536차원은 애플리케이션 코드와 mapping에 고정되어 있습니다. 다른 차원의 모델을 사용하려면 코드의 인덱스 설정 및 vector mapping을 바꾸고, 그에 맞는 새 인덱스 생성·전체 재색인·embedding Backfill·검색 대상 전환을 먼저 준비해야 합니다. 기존 인덱스의 vector 차원을 단순 변경해 재사용할 수 없습니다.

모델명만 바뀌고 embedding 입력 hash가 그대로라면 재계산 필요 여부는 저장된 `embeddingModel`과 현재 모델 비교로 판정되므로, 모델 변경만을 이유로 `SYNC_DATA`가 항상 필요한 것은 아닙니다. 필요 시 `SYNC_DATA`를 먼저 실행하고 이후 `EMBEDDING_BACKFILL`을 실행합니다. 다만 #12 최초 배포에는 신규 `contentInputHash` 반영과 구버전 writer 위험 통제를 위해 위 절차대로 `SYNC_DATA`를 실행해야 합니다.
