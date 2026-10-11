# 검색 데이터 재색인 CLI 운영 안내

`reindex-cli`는 API 서버와 분리된 일회성 실행 도구입니다. 실행 한 번에 모드를 하나만 선택합니다. API의 웹 서버, Kafka Listener, 스케줄러와 관리자 초기화 작업은 시작하지 않습니다.

## 사전 준비

- Java 21과 저장소의 Gradle Wrapper를 사용합니다.
- MySQL과 Elasticsearch가 실행 중이어야 합니다. 접속 정보는 `.env.example`을 참고해 PowerShell 프로세스 환경 변수로 설정합니다. `.env.example` 자체가 자동으로 로드되지는 않습니다.
- 별도 Spring 프로필은 필요하지 않습니다. `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `ELASTICSEARCH_URL`을 환경에 맞게 설정하세요. CLI 초기화 코드가 이 값을 각각 datasource와 Elasticsearch 설정으로 변환합니다.
- 재색인 CLI는 Flyway 마이그레이션을 실행하지 않습니다. 배포된 API 또는 별도 마이그레이션 절차로 DB 스키마를 먼저 최신 상태로 맞추세요. CLI의 Hibernate 스키마 동작은 `validate` 전용이며 테이블을 생성하거나 변경하지 않습니다.

## SYNC_DATA 실행

MySQL 콘텐츠를 기존 검색 동기화 로직으로 Elasticsearch에 반영합니다. Embedding Backfill은 실행하지 않습니다.
이 모드에는 `MOPL_AI_ENABLED`나 `OPENAI_API_KEY`가 필요하지 않습니다.

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
| `0` | 선택한 작업이 정상 완료됨 | 추가 조치가 필요하지 않습니다. Backfill 대상이 이미 없거나 모두 기존 Embedding 보유 문서여도 정상 완료일 수 있습니다. |
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
export MOPL_AI_ENABLED='true'
read -rsp 'OPENAI_API_KEY: ' OPENAI_API_KEY
export OPENAI_API_KEY
printf '\n'
export MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEFAILURES='5'
export MOPL_ELASTICSEARCH_EMBEDDINGBACKFILL_MAXCONSECUTIVEESFAILURES='5'

if [[ -z ${cli_jar:-} ]]; then
    printf '실행 가능한 JAR가 없어 EMBEDDING_BACKFILL을 시작하지 않습니다.\n' >&2
    cli_exit_code=1
elif java -jar "$cli_jar" --mopl.reindex.mode=EMBEDDING_BACKFILL; then
    cli_exit_code=0
else
    cli_exit_code=$?
fi
unset OPENAI_API_KEY
printf 'CLI exit code: %d\n' "$cli_exit_code"
```

`if`에서 `java -jar`를 실행하면 호출 스크립트가 `set -e`를 사용해도 CLI의 `1`, `2`, `3`을 캡처한 뒤 대응할 수 있습니다. 종료 코드는 다른 외부 명령을 실행하기 전에 `$?`에서 `cli_exit_code`로 보관합니다. 호출 스크립트가 CLI와 같은 코드로 종료해야 한다면 마지막에 다음을 실행하세요. 대화형 셸에서 실행하면 현재 셸이 종료됩니다.

```bash
exit "$cli_exit_code"
```

## Backfill 재실행과 처리 특성

- Backfill은 Elasticsearch에서 `embedding`이 없는 문서만 찾습니다. 이미 Embedding이 저장된 문서는 다음 실행에서 조회 대상이 아니므로 OpenAI를 다시 호출하지 않습니다.
- 검색 Scroll을 닫은 뒤 조회된 전체 고유 UUID를 메모리에 수집하고, 이후 MySQL의 활성 콘텐츠 및 태그를 500개 단위로 조회합니다. 따라서 현재 방식은 전체 누락 UUID 수에 비례해 메모리를 사용합니다. 데이터 규모가 크게 늘면 영속 체크포인트 또는 페이지 기반 설계를 별도 검토해야 합니다.
- MySQL에서 찾을 수 없거나 `deletedAt`이 설정된 콘텐츠는 건너뜁니다. Backfill은 일반 검색 문서를 새로 만들지 않습니다.
- OpenAI 인증 오류 401/403은 즉시 중단합니다. 다른 OpenAI 실패와 Elasticsearch 저장 실패는 서로 분리된 연속 실패 임계치를 사용합니다. 해당 임계치에 도달하면 남은 문서는 미처리로 기록되고 종료 코드 `1`이 반환됩니다.
- 일부 성공 후 실패해도 성공한 Embedding은 보존됩니다. 원인을 해결한 후 같은 Backfill 명령을 다시 실행하면 이미 저장된 Embedding은 제외되고 누락된 문서부터 이어서 처리합니다. 복잡한 재시도 큐나 별도 체크포인트는 사용하지 않습니다.
- 종료 코드 `3`이거나 검색/DB 처리 중 CLI가 예외로 종료됐다면 로그의 원인을 먼저 해결한 뒤 재실행합니다. Scroll 조회 실패는 불완전한 검색 ID 목록으로 처리를 시작하지 않습니다.
