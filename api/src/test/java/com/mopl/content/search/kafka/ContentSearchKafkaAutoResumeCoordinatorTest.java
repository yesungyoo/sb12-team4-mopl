package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.FailureClassification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class ContentSearchKafkaAutoResumeCoordinatorTest {

    private static final String LISTENER_ID = "content-search-listener";
    private static final TopicPartition PARTITION = new TopicPartition("content-search-sync", 0);
    private static final ContentSearchKafkaAutoResumeCoordinator.ProbeSettings SETTINGS =
            new ContentSearchKafkaAutoResumeCoordinator.ProbeSettings(10, 2, 3, 10);

    private final ContentSearchKafkaFailureClassifier classifier = new ContentSearchKafkaFailureClassifier();

    @Test
    void resumesOnlyAfterMinimumPauseAndThreeConsecutiveSuccessfulProbesThenWaitsForResumeToApply() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        AtomicBoolean paused = new AtomicBoolean(true);
        when(container.isPartitionPaused(PARTITION)).thenAnswer(invocation -> paused.get());
        doAnswer(invocation -> {
            paused.set(false);
            return null;
        }).when(container).resumePartition(PARTITION);

        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock,
                (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );

        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.probePausedPartitions();
        assertThat(probeCount).hasValue(0);
        verify(container, never()).resumePartition(PARTITION);

        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(container, never()).resumePartition(PARTITION);
        coordinator.probePausedPartitions();

        verify(container).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(3);
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);

        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isZero();
        assertThat(probeCount).hasValue(3);
        coordinator.shutdown();
    }

    @Test
    void failedProbeResetsSuccessCountAndNewFailureDoesNotReusePreviousProbeSuccesses() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.MYSQL, clock,
                (probe, timeout) -> probeCount.incrementAndGet() != 2
        );

        coordinator.recordFailure(LISTENER_ID, PARTITION, 10,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, mysqlTransientFailure());
        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // success 1
        coordinator.probePausedPartitions(); // failure resets
        coordinator.probePausedPartitions(); // success 1
        coordinator.recordFailure(LISTENER_ID, PARTITION, 11,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT,
                mysqlTransientFailure()); // a new failure resets
        coordinator.probePausedPartitions(); // success 1 for the new failure

        verify(container, never()).resumePartition(PARTITION);
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        coordinator.shutdown();
    }

    @Test
    void nonEligibleFailureInvalidatesPendingAutoResumeState() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock, (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);

        coordinator.recordFailure(
                LISTENER_ID,
                PARTITION,
                42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.AMBIGUOUS,
                classifier.classifyWithDependency(new org.springframework.data.elasticsearch.RestStatusException(
                        429, "rate limited"
                ))
        );

        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.probePausedPartitions();
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void dltPublishFailureNeverRegistersForAutomaticResumeEvenIfItsCauseLooksTransient() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, new MutableClock(), (probe, timeout) -> true
        );

        coordinator.recordFailure(
                LISTENER_ID,
                PARTITION,
                42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.DLT_PUBLISH_FAILURE,
                esTransientFailure()
        );
        coordinator.probePausedPartitions();

        assertThat(coordinator.trackedPauseCount()).isZero();
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void openAiTransientFailureIsNotRegisteredForAutomaticResume() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, new MutableClock(), (probe, timeout) -> true
        );

        coordinator.recordFailure(
                LISTENER_ID,
                PARTITION,
                42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT,
                classifier.classifyWithDependency(new com.mopl.infrastructure.ai.exception.AiClientException(
                        "OpenAI unavailable", 503, null
                ))
        );

        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.probePausedPartitions();
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void refusesResumeWhenListenerStoppedOrPartitionIsNoLongerAssigned() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock, (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        clock.advanceMillis(10);
        when(container.isRunning()).thenReturn(false);
        coordinator.probePausedPartitions();
        verify(container, never()).resumePartition(PARTITION);
        assertThat(coordinator.trackedPauseCount()).isZero();

        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of());
        coordinator.recordFailure(LISTENER_ID, PARTITION, 43,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.probePausedPartitions();
        verify(container, never()).resumePartition(PARTITION);
        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.shutdown();
    }

    @Test
    void revocationClearsTheOldOwnersPendingResumeState() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, new MutableClock(), (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));

        assertThat(coordinator.trackedPauseCount()).isZero();
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void assignmentClearsAnyPreviousPauseStateForThePartition() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        pausedAssignedContainer(registry);
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, new MutableClock(), (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());

        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));

        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.shutdown();
    }

    @Test
    void lateProbeCompletionIsNotCountedAndSameDependencyProbeCannotOverlap() throws Exception {
        ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker invoker =
                new ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ContentSearchDependencyHealthProbe probe = new ContentSearchDependencyHealthProbe() {
            @Override
            public Dependency dependency() {
                return Dependency.ELASTICSEARCH;
            }

            @Override
            public void checkHealthy() {
                calls.incrementAndGet();
                try {
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Simulate an I/O client that does not stop immediately on timeout cancellation.
                        }
                    }
                } finally {
                    finished.countDown();
                }
            }
        };

        try {
            assertThat(invoker.check(probe, 20)).isFalse();
            assertThat(invoker.check(probe, 20)).isFalse();
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(invoker.check(probe, 1000)).isTrue();
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            invoker.close();
        }
    }

    @Test
    void reassignmentToAnotherConcurrentConsumerInvalidatesThePreviousOwnersState() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer oldOwner = mock(MessageListenerContainer.class);
        MessageListenerContainer newOwner = mock(MessageListenerContainer.class);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenReturn(oldOwner, oldOwner, newOwner);
        when(oldOwner.isRunning()).thenReturn(true);
        when(oldOwner.getAssignedPartitions()).thenReturn(Set.of(PARTITION), Set.of());
        when(oldOwner.isPartitionPaused(PARTITION)).thenReturn(true);
        when(newOwner.isRunning()).thenReturn(true);
        when(newOwner.getAssignedPartitions()).thenReturn(Set.of(PARTITION));
        when(newOwner.isPartitionPaused(PARTITION)).thenReturn(true);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock,
                (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());

        coordinator.probePausedPartitions(); // observes that owner A is assigned and actually paused

        assertThat(probeCount).hasValue(0);
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        verify(oldOwner, never()).resumePartition(PARTITION);
        verify(newOwner, never()).resumePartition(PARTITION);

        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // owner A remains the owner for the first successful probe

        assertThat(probeCount).hasValue(1);
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        verify(oldOwner, never()).resumePartition(PARTITION);
        verify(newOwner, never()).resumePartition(PARTITION);

        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // owner B is now assigned; owner A's state must be discarded

        assertThat(coordinator.trackedPauseCount()).isZero();
        assertThat(probeCount).hasValue(1);
        verify(container, times(3)).getContainerFor(PARTITION.topic(), PARTITION.partition());
        verify(oldOwner, never()).resumePartition(PARTITION);
        verify(newOwner, never()).resumePartition(PARTITION);
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    private ContentSearchKafkaAutoResumeCoordinator coordinator(
            KafkaListenerEndpointRegistry registry,
            Dependency dependency,
            MutableClock clock,
            ContentSearchKafkaAutoResumeCoordinator.ProbeInvoker probeInvoker
    ) {
        ContentSearchDependencyHealthProbe probe = new ContentSearchDependencyHealthProbe() {
            @Override
            public Dependency dependency() {
                return dependency;
            }

            @Override
            public void checkHealthy() {
            }
        };
        return new ContentSearchKafkaAutoResumeCoordinator(
                registry, List.of(probe), SETTINGS, clock, probeInvoker
        );
    }

    private MessageListenerContainer pausedAssignedContainer(KafkaListenerEndpointRegistry registry) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(LISTENER_ID)).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of(PARTITION));
        when(container.isPartitionPauseRequested(PARTITION)).thenReturn(true);
        when(container.isPartitionPaused(PARTITION)).thenReturn(true);
        return container;
    }

    private FailureClassification esTransientFailure() {
        return classifier.classifyWithDependency(
                new org.springframework.data.elasticsearch.RestStatusException(503, "cluster unavailable")
        );
    }

    private FailureClassification mysqlTransientFailure() {
        return classifier.classifyWithDependency(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("connection unavailable")
        );
    }

    private static final class MutableClock extends Clock {

        private long currentMillis;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }

        private void advanceMillis(long amount) {
            currentMillis += amount;
        }
    }
}
