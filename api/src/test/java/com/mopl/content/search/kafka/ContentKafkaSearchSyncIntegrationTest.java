package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentResponse;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.document.ContentSearchDocument;
import com.mopl.content.search.repository.ContentSearchRepository;
import com.mopl.content.service.ContentService;
import com.mopl.core.common.enums.ContentType;
import com.mopl.infrastructure.ai.client.EmbeddingClient;
import com.mopl.infrastructure.ai.dto.EmbeddingRequest;
import com.mopl.infrastructure.ai.dto.EmbeddingResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "mopl.ai.enabled=false",
        "mopl.elasticsearch.reindex-on-startup=false"
})
@EmbeddedKafka(
        partitions = 1,
        topics = ContentSearchKafkaTopics.CONTENT_SEARCH_SYNC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
class ContentKafkaSearchSyncIntegrationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final int KAFKA_PARTITIONS = 1;
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(10);

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("mopl")
                    .withUsername("mopl")
                    .withPassword("mopl")
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse(
                            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8"
                    )
            )
                    .withEnv(
                            "xpack.security.enabled",
                            "false"
                    )
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentSearchRepository contentSearchRepository;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                MYSQL::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                MYSQL::getUsername
        );

        registry.add(
                "spring.datasource.password",
                MYSQL::getPassword
        );

        registry.add(
                "spring.elasticsearch.uris",
                ELASTICSEARCH::getHttpHostAddress
        );
    }

    @BeforeEach
    void setUp() {
        waitForKafkaConsumerAssignment();

        contentSearchRepository.deleteAll();
        contentRepository.deleteAllInBatch();

        when(
                embeddingClient.embed(
                        any(EmbeddingRequest.class)
                )
        ).thenReturn(
                new EmbeddingResponse(
                        createEmbedding()
                )
        );
    }

    @Test
    void synchronizesContentCrudToElasticsearchThroughKafka()
            throws InterruptedException {
        ContentCreateRequest createRequest =
                new ContentCreateRequest(
                        ContentType.MOVIE,
                        "Kafka 생성 영화",
                        "Kafka를 통해 Elasticsearch에 동기화되는 콘텐츠",
                        "https://example.com/kafka-created.jpg",
                        LocalDate.of(
                                2026,
                                10,
                                3
                        )
                );

        ContentResponse created =
                contentService.createContent(
                        createRequest
                );

        awaitUntil(
                () -> contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .map(ContentSearchDocument::getTitle)
                        .filter("Kafka 생성 영화"::equals)
                        .isPresent()
        );

        ContentSearchDocument createdDocument =
                contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .orElseThrow();

        assertThat(createdDocument.getTitle())
                .isEqualTo("Kafka 생성 영화");

        assertThat(createdDocument.getDescription())
                .isEqualTo(
                        "Kafka를 통해 Elasticsearch에 동기화되는 콘텐츠"
                );

        ContentUpdateRequest updateRequest =
                new ContentUpdateRequest(
                        ContentType.MOVIE,
                        "Kafka 수정 영화",
                        "수정 이벤트도 Kafka를 통해 동기화",
                        "https://example.com/kafka-updated.jpg",
                        LocalDate.of(
                                2026,
                                10,
                                4
                        )
                );

        contentService.updateContent(
                created.id(),
                updateRequest
        );

        awaitUntil(
                () -> contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .map(ContentSearchDocument::getTitle)
                        .filter("Kafka 수정 영화"::equals)
                        .isPresent()
        );

        ContentSearchDocument updatedDocument =
                contentSearchRepository
                        .findById(
                                created.id().toString()
                        )
                        .orElseThrow();

        assertThat(updatedDocument.getId())
                .isEqualTo(
                        created.id().toString()
                );

        assertThat(updatedDocument.getTitle())
                .isEqualTo("Kafka 수정 영화");

        assertThat(updatedDocument.getDescription())
                .isEqualTo(
                        "수정 이벤트도 Kafka를 통해 동기화"
                );

        contentService.deleteContent(
                created.id()
        );

        awaitUntil(
                () -> !contentSearchRepository.existsById(
                        created.id().toString()
                )
        );

        assertThat(
                contentSearchRepository.existsById(
                        created.id().toString()
                )
        ).isFalse();

        assertThat(
                contentRepository.findByIdAndDeletedAtIsNull(
                        created.id()
                )
        ).isEmpty();
    }

    private void waitForKafkaConsumerAssignment() {
        for (
                MessageListenerContainer listenerContainer
                : kafkaListenerEndpointRegistry.getListenerContainers()
        ) {
            ContainerTestUtils.waitForAssignment(
                    listenerContainer,
                    KAFKA_PARTITIONS
            );
        }
    }

    private List<Double> createEmbedding() {
        return IntStream
                .range(
                        0,
                        EMBEDDING_DIMENSIONS
                )
                .mapToObj(index ->
                        index == 0
                                ? 1.0
                                : 0.0
                )
                .toList();
    }

    private void awaitUntil(
            BooleanSupplier condition
    ) throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + SYNC_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(100);
        }

        assertThat(condition.getAsBoolean())
                .as(
                        "Kafka 이벤트가 제한 시간 내 Elasticsearch에 반영되어야 한다."
                )
                .isTrue();
    }
}
