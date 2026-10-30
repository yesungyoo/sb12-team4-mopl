package com.mopl.content.search.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.Dependency;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.FailureClassification;
import com.mopl.content.search.kafka.ContentSearchKafkaFailureClassifier.FailureType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class ContentSearchKafkaAutoResumeCoordinatorTest {

    private static final String LISTENER_ID = "content-search-listener";
    private static final TopicPartition PARTITION = new TopicPartition("content-search-sync", 0);
    private static final TopicPartition PARTITION_A = new TopicPartition("content-search-sync", 1);
    private static final TopicPartition PARTITION_B = new TopicPartition("content-search-sync", 2);
    private static final ContentSearchKafkaAutoResumeCoordinator.ProbeSettings SETTINGS =
            new ContentSearchKafkaAutoResumeCoordinator.ProbeSettings(10, 2, 3, 10);

    private final ContentSearchKafkaFailureClassifier classifier = new ContentSearchKafkaFailureClassifier();

    @Test
    void resumesOnlyAfterMinimumPauseAndThreeConsecutiveSuccessfulProbesThenWaitsForResumeToApply() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        AtomicBoolean paused = new AtomicBoolean(true);
        AtomicBoolean pauseRequested = new AtomicBoolean(true);
        when(container.isPartitionPaused(PARTITION)).thenAnswer(invocation -> paused.get());
        when(container.isPartitionPauseRequested(PARTITION)).thenAnswer(invocation -> pauseRequested.get());
        doAnswer(invocation -> {
            paused.set(false);
            pauseRequested.set(false);
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
        assertThat(pauseRequested).isFalse();
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
    void unknownDependencyFailureIsNotRegisteredForAutomaticResume() {
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
                new FailureClassification(FailureType.COMMON_TRANSIENT, Dependency.UNKNOWN)
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
    void revocationRetainsEligibleDependencyButDetachesPreviousOwner() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, new MutableClock(), (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));

        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        verify(container, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void reassignmentToSameOwnerRetainsPauseAndRequiresFreshSuccessfulProbes() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer owner = pausedOwner(Set.of(PARTITION));
        AtomicBoolean paused = new AtomicBoolean(true);
        AtomicBoolean pauseRequested = new AtomicBoolean(true);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition())).thenReturn(owner);
        when(owner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> paused.get());
        when(owner.isPartitionPauseRequested(PARTITION)).thenAnswer(invocation -> pauseRequested.get());
        doAnswer(invocation -> {
            paused.set(false);
            pauseRequested.set(false);
            return null;
        }).when(owner).resumePartition(PARTITION);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock, (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );

        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.probePausedPartitions(); // initial owner and actual pause are recorded
        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // one success belongs to the pre-rebalance state
        assertThat(probeCount).hasValue(1);

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
		assertThat(paused.get()).isTrue();

        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // observes pause after reassignment; old success is discarded
        assertThat(probeCount).hasValue(1);
        verify(owner, never()).resumePartition(PARTITION);
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(owner, never()).resumePartition(PARTITION);
        coordinator.probePausedPartitions();

        verify(owner).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(4);
        coordinator.shutdown();
    }

    @Test
    void reassignmentToNewOwnerResumesOnlyAfterFreshElasticsearchProbes() {
        verifyReassignmentToNewOwnerResumes(Dependency.ELASTICSEARCH, esTransientFailure());
    }

    @Test
    void reassignmentToNewOwnerResumesOnlyAfterFreshMysqlProbes() {
        verifyReassignmentToNewOwnerResumes(Dependency.MYSQL, mysqlTransientFailure());
    }

    // 아래 두 테스트에 공통 적용되는 방어적 시나리오다. Spring Kafka 3.3.x의
    // ConcurrentMessageListenerContainer는 부모 Stop/Start 시 child 컨테이너를 새로 생성하므로,
    // 일반적인 Stop/Start 동작을 재현하지 않는다. 기존 child 객체와 Pause 요청이 남아 재사용되는
    // 경우에도 복구 상태가 안전한지 확인한다.
    @Test
    void reusedChildRetainsElasticsearchRecoveryAcrossListenerStopStart() {
        verifyReusedChildRecoveryAcrossStopStart(Dependency.ELASTICSEARCH, esTransientFailure());
    }

    @Test
    void reusedChildRetainsMysqlRecoveryAcrossListenerStopStart() {
        verifyReusedChildRecoveryAcrossStopStart(Dependency.MYSQL, mysqlTransientFailure());
    }

    @Test
    void recreatedChildDoesNotReceiveStaleResumeWhenPauseRequestWasNotTransferred() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer parent = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(LISTENER_ID)).thenReturn(parent);
        AtomicBoolean listenerRunning = new AtomicBoolean(true);
        AtomicReference<Set<TopicPartition>> parentAssigned = new AtomicReference<>(Set.of(PARTITION));
        when(parent.isRunning()).thenAnswer(invocation -> listenerRunning.get());
        when(parent.getAssignedPartitions()).thenAnswer(invocation -> parentAssigned.get());

        MessageListenerContainer oldChild = mock(MessageListenerContainer.class);
        AtomicBoolean oldChildRunning = new AtomicBoolean(true);
        AtomicBoolean oldChildPaused = new AtomicBoolean(true);
        AtomicBoolean oldChildPauseRequested = new AtomicBoolean(true);
        AtomicReference<Set<TopicPartition>> oldChildAssigned = new AtomicReference<>(Set.of(PARTITION));
        configureRestartOwner(oldChild, oldChildRunning, oldChildAssigned, oldChildPaused, oldChildPauseRequested);

        MessageListenerContainer newChild = mock(MessageListenerContainer.class);
        AtomicReference<MessageListenerContainer> currentChild = new AtomicReference<>(oldChild);
        AtomicBoolean newChildPaused = new AtomicBoolean(false);
        AtomicBoolean newChildPauseRequested = new AtomicBoolean(false);
        AtomicReference<Set<TopicPartition>> newChildAssigned = new AtomicReference<>(Set.of());
        configureRestartOwner(newChild, new AtomicBoolean(true), newChildAssigned,
                newChildPaused, newChildPauseRequested);
        when(parent.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenAnswer(invocation -> currentChild.get());

        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock, (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.probePausedPartitions();

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        listenerRunning.set(false);
        parentAssigned.set(Set.of());
        oldChildRunning.set(false);
        oldChildAssigned.set(Set.of());
        oldChildPaused.set(false);
        assertThat(oldChildPauseRequested).isTrue();
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount())
                .as("정지 중 실제 Pause가 풀려도 요청 marker가 남아 있으면 복구 자격을 보존합니다")
                .isEqualTo(1);

        listenerRunning.set(true);
        currentChild.set(newChild);
        parentAssigned.set(Set.of(PARTITION));
        newChildAssigned.set(Set.of(PARTITION));
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        assertThat(newChildPaused).isFalse();
        assertThat(newChildPauseRequested).isFalse();

        clock.advanceMillis(31);
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isZero();
        verify(oldChild, never()).resumePartition(PARTITION);
        verify(newChild, never()).resumePartition(PARTITION);
        coordinator.shutdown();
    }

    @Test
    void concurrentChildrenCanReassignAFirstWithoutDeletingBRecoveryState() {
        verifyConcurrentChildReassignmentPreservesBothStates(true);
    }

    @Test
    void concurrentChildrenCanReassignBFirstWithoutDeletingARecoveryState() {
        verifyConcurrentChildReassignmentPreservesBothStates(false);
    }

    @Test
    void pauseMarkerOnFormerOwnerRemainsRecoverableAfterPartitionMovesAwayAndBack() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer formerOwner = pausedOwner(Set.of(PARTITION));
        MessageListenerContainer currentOwner = pausedOwner(Set.of(PARTITION));
        AtomicReference<MessageListenerContainer> assignedOwner = new AtomicReference<>(formerOwner);
        AtomicBoolean formerOwnerPaused = new AtomicBoolean(true);
        AtomicBoolean formerOwnerPauseRequested = new AtomicBoolean(true);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenAnswer(invocation -> assignedOwner.get());
        when(formerOwner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> formerOwnerPaused.get());
        when(formerOwner.isPartitionPauseRequested(PARTITION))
                .thenAnswer(invocation -> formerOwnerPauseRequested.get());
        when(currentOwner.isPartitionPaused(PARTITION)).thenReturn(false);
        when(currentOwner.isPartitionPauseRequested(PARTITION)).thenReturn(false);
        doAnswer(invocation -> {
            formerOwnerPaused.set(false);
            formerOwnerPauseRequested.set(false);
            return null;
        }).when(formerOwner).resumePartition(PARTITION);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock, (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );

        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.probePausedPartitions();
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        assertThat(probeCount).hasValue(1);

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        when(formerOwner.getAssignedPartitions()).thenReturn(Set.of());
        assignedOwner.set(currentOwner);
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // the new owner is not paused, but the former owner's request remains

        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        assertThat(probeCount).hasValue(1);
        verify(formerOwner, never()).resumePartition(PARTITION);
        verify(currentOwner, never()).resumePartition(PARTITION);

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        when(currentOwner.getAssignedPartitions()).thenReturn(Set.of());
        assignedOwner.set(formerOwner);
        when(formerOwner.getAssignedPartitions()).thenReturn(Set.of(PARTITION));
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        coordinator.probePausedPartitions(); // the old owner is current again and its persistent pause is observed
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(formerOwner, never()).resumePartition(PARTITION);
        coordinator.probePausedPartitions();

        verify(formerOwner).resumePartition(PARTITION);
        verify(currentOwner, never()).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(4);
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.shutdown();
    }

    private void verifyReassignmentToNewOwnerResumes(Dependency dependency, FailureClassification failure) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer oldOwner = pausedOwner(Set.of(PARTITION));
        MessageListenerContainer newOwner = pausedOwner(Set.of(PARTITION));
        AtomicBoolean oldOwnerPaused = new AtomicBoolean(true);
        AtomicBoolean newOwnerPaused = new AtomicBoolean(true);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenReturn(oldOwner, oldOwner, newOwner, newOwner, newOwner, newOwner, newOwner);
        when(oldOwner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> oldOwnerPaused.get());
        when(newOwner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> newOwnerPaused.get());
        doAnswer(invocation -> {
            newOwnerPaused.set(false);
            return null;
        }).when(newOwner).resumePartition(PARTITION);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, dependency, clock, (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );

        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, failure);
        coordinator.probePausedPartitions();
        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // one old-owner probe success
        assertThat(probeCount).hasValue(1);

        oldOwnerPaused.set(false);
        when(oldOwner.getAssignedPartitions()).thenReturn(Set.of());
        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        verify(oldOwner, never()).resumePartition(PARTITION);

        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // new owner sees retained pause; prior successes reset
        assertThat(probeCount).hasValue(1);
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(newOwner, never()).resumePartition(PARTITION);
        verify(oldOwner, never()).resumePartition(PARTITION);
        coordinator.probePausedPartitions();

        verify(newOwner).resumePartition(PARTITION);
        verify(oldOwner, never()).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(4);
        coordinator.shutdown();
    }

    private void verifyConcurrentChildReassignmentPreservesBothStates(boolean assignAFirst) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer parent = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(LISTENER_ID)).thenReturn(parent);
        when(parent.isRunning()).thenReturn(true);
        AtomicReference<Set<TopicPartition>> parentAssigned = new AtomicReference<>(Set.of(PARTITION_A, PARTITION_B));
        when(parent.getAssignedPartitions()).thenAnswer(invocation -> parentAssigned.get());

        MessageListenerContainer ownerA = mock(MessageListenerContainer.class);
        MessageListenerContainer ownerB = mock(MessageListenerContainer.class);
        AtomicReference<Set<TopicPartition>> assignedA = new AtomicReference<>(Set.of(PARTITION_A));
        AtomicReference<Set<TopicPartition>> assignedB = new AtomicReference<>(Set.of(PARTITION_B));
        AtomicBoolean pausedA = new AtomicBoolean(true);
        AtomicBoolean pausedB = new AtomicBoolean(true);
        AtomicBoolean pauseRequestedA = new AtomicBoolean(true);
        AtomicBoolean pauseRequestedB = new AtomicBoolean(true);
        configureConcurrentOwner(ownerA, PARTITION_A, assignedA, pausedA, pauseRequestedA);
        configureConcurrentOwner(ownerB, PARTITION_B, assignedB, pausedB, pauseRequestedB);
        when(parent.getContainerFor(PARTITION_A.topic(), PARTITION_A.partition()))
                .thenReturn(ownerA);
        when(parent.getContainerFor(PARTITION_B.topic(), PARTITION_B.partition()))
                .thenReturn(ownerB);

        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = new ContentSearchKafkaAutoResumeCoordinator(
                registry,
                List.of(
                        probe(Dependency.ELASTICSEARCH),
                        probe(Dependency.MYSQL)
                ),
                SETTINGS,
                clock,
                (probe, timeout) -> true
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION_A, 10,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, esTransientFailure());
        coordinator.recordFailure(LISTENER_ID, PARTITION_B, 20,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, mysqlTransientFailure());
        coordinator.probePausedPartitions();
        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // each eligible dependency has a pre-rebalance success

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION_A));
        assignedA.set(Set.of());
        parentAssigned.set(Set.of(PARTITION_B));
        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION_B));
        assignedB.set(Set.of());
        parentAssigned.set(Set.of());

        if (assignAFirst) {
            assignConcurrentPartition(coordinator, PARTITION_A, ownerA, assignedA, parentAssigned,
                    Set.of(PARTITION_A));
            coordinator.probePausedPartitions();
            assertThat(coordinator.trackedPauseCount())
                    .as("B의 자격은 B child의 할당 콜백 전까지 보존되어야 합니다")
                    .isEqualTo(2);
            verify(ownerA, never()).resumePartition(PARTITION_A);
            verify(ownerB, never()).resumePartition(PARTITION_B);
            assignConcurrentPartition(coordinator, PARTITION_B, ownerB, assignedB, parentAssigned,
                    Set.of(PARTITION_A, PARTITION_B));
        } else {
            assignConcurrentPartition(coordinator, PARTITION_B, ownerB, assignedB, parentAssigned,
                    Set.of(PARTITION_B));
            coordinator.probePausedPartitions();
            assertThat(coordinator.trackedPauseCount())
                    .as("A의 자격은 A child의 할당 콜백 전까지 보존되어야 합니다")
                    .isEqualTo(2);
            verify(ownerA, never()).resumePartition(PARTITION_A);
            verify(ownerB, never()).resumePartition(PARTITION_B);
            assignConcurrentPartition(coordinator, PARTITION_A, ownerA, assignedA, parentAssigned,
                    Set.of(PARTITION_A, PARTITION_B));
        }

        assertThat(coordinator.trackedPauseCount()).isEqualTo(2);
        coordinator.probePausedPartitions(); // observe both pauses under the new assignments
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(ownerA, never()).resumePartition(PARTITION_A);
        verify(ownerB, never()).resumePartition(PARTITION_B);
        coordinator.probePausedPartitions(); // third fresh success for ES and MySQL

        verify(ownerA).resumePartition(PARTITION_A);
        verify(ownerB).resumePartition(PARTITION_B);
        verify(ownerA, never()).resumePartition(PARTITION_B);
        verify(ownerB, never()).resumePartition(PARTITION_A);
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.shutdown();
    }

    private void verifyReusedChildRecoveryAcrossStopStart(
            Dependency dependency,
            FailureClassification failure
    ) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer parent = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(LISTENER_ID)).thenReturn(parent);
        AtomicBoolean listenerRunning = new AtomicBoolean(true);
        AtomicReference<Set<TopicPartition>> parentAssigned = new AtomicReference<>(Set.of(PARTITION));
        when(parent.isRunning()).thenAnswer(invocation -> listenerRunning.get());
        when(parent.getAssignedPartitions()).thenAnswer(invocation -> parentAssigned.get());

        MessageListenerContainer reusedChild = mock(MessageListenerContainer.class);
        AtomicBoolean childRunning = new AtomicBoolean(true);
        AtomicBoolean childPaused = new AtomicBoolean(true);
        AtomicBoolean childPauseRequested = new AtomicBoolean(true);
        AtomicReference<Set<TopicPartition>> childAssigned = new AtomicReference<>(Set.of(PARTITION));
        configureRestartOwner(reusedChild, childRunning, childAssigned, childPaused, childPauseRequested);
        when(parent.getContainerFor(PARTITION.topic(), PARTITION.partition())).thenReturn(reusedChild);
        doAnswer(invocation -> {
            childPaused.set(false);
            childPauseRequested.set(false);
            return null;
        }).when(reusedChild).resumePartition(PARTITION);

        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, dependency, clock, (probe, timeout) -> {
                    probeCount.incrementAndGet();
                    return true;
                }
        );
        coordinator.recordFailure(LISTENER_ID, PARTITION, 42,
                ContentSearchKafkaAutoResumeCoordinator.PauseReason.COMMON_TRANSIENT, failure);
        coordinator.probePausedPartitions();

        coordinator.onPartitionsRevoked(LISTENER_ID, List.of(PARTITION));
        listenerRunning.set(false);
        parentAssigned.set(Set.of());
        childRunning.set(false);
        childAssigned.set(Set.of());
        childPaused.set(false);
        coordinator.probePausedPartitions();

        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        assertThat(probeCount).hasValue(0);
        assertThat(childPauseRequested).isTrue();

        listenerRunning.set(true);
        childRunning.set(true);
        parentAssigned.set(Set.of(PARTITION));
        childAssigned.set(Set.of(PARTITION));
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(PARTITION));
        coordinator.probePausedPartitions(); // request remains, but actual Pause has not yet been applied
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        assertThat(probeCount).hasValue(0);
        verify(reusedChild, never()).resumePartition(PARTITION);

        childPaused.set(true); // Spring Kafka applies the retained request on the consumer thread
        coordinator.probePausedPartitions();
        clock.advanceMillis(10);
        coordinator.probePausedPartitions();
        coordinator.probePausedPartitions();
        verify(reusedChild, never()).resumePartition(PARTITION);
        coordinator.probePausedPartitions();

        verify(reusedChild).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(3);
        assertThat(childPauseRequested).isFalse();
        coordinator.probePausedPartitions();
        assertThat(coordinator.trackedPauseCount()).isZero();
        coordinator.shutdown();
    }

    private void configureRestartOwner(
            MessageListenerContainer owner,
            AtomicBoolean running,
            AtomicReference<Set<TopicPartition>> assigned,
            AtomicBoolean paused,
            AtomicBoolean pauseRequested
    ) {
        when(owner.isRunning()).thenAnswer(invocation -> running.get());
        when(owner.getAssignedPartitions()).thenAnswer(invocation -> assigned.get());
        when(owner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> paused.get());
        when(owner.isPartitionPauseRequested(PARTITION)).thenAnswer(invocation -> pauseRequested.get());
    }

    private void assignConcurrentPartition(
            ContentSearchKafkaAutoResumeCoordinator coordinator,
            TopicPartition partition,
            MessageListenerContainer owner,
            AtomicReference<Set<TopicPartition>> childAssigned,
            AtomicReference<Set<TopicPartition>> parentAssigned,
            Set<TopicPartition> assignedOnParent
    ) {
        childAssigned.set(Set.of(partition));
        parentAssigned.set(assignedOnParent);
        coordinator.onPartitionsAssigned(LISTENER_ID, List.of(partition));
        assertThat(owner.isRunning()).isTrue();
    }

    private void configureConcurrentOwner(
            MessageListenerContainer owner,
            TopicPartition partition,
            AtomicReference<Set<TopicPartition>> assigned,
            AtomicBoolean paused,
            AtomicBoolean pauseRequested
    ) {
        when(owner.isRunning()).thenReturn(true);
        when(owner.getAssignedPartitions()).thenAnswer(invocation -> assigned.get());
        when(owner.isPartitionPaused(partition)).thenAnswer(invocation -> paused.get());
        when(owner.isPartitionPauseRequested(partition)).thenAnswer(invocation -> pauseRequested.get());
        doAnswer(invocation -> {
            paused.set(false);
            pauseRequested.set(false);
            return null;
        }).when(owner).resumePartition(partition);
    }

    private ContentSearchDependencyHealthProbe probe(Dependency dependency) {
        return new ContentSearchDependencyHealthProbe() {
            @Override
            public Dependency dependency() {
                return dependency;
            }

            @Override
            public void checkHealthy() {
            }
        };
    }

    @Test
    void runningProbeTimeoutDoesNotAllowOverlapAndLateSuccessIsIgnored() throws Exception {
        ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker invoker =
                new ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
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
                started.countDown();
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
            Future<Boolean> timedOutCheck = caller.submit(() -> invoker.check(probe, 20));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(timedOutCheck.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(invoker.check(probe, 20)).isFalse();
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean recovered = false;
            while (!recovered && System.nanoTime() < deadline) {
                recovered = invoker.check(probe, 1000);
                Thread.yield();
            }
            assertThat(recovered).isTrue();
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            caller.shutdownNow();
            invoker.close();
        }
    }

    @Test
    void reassignmentToAnotherConcurrentConsumerInvalidatesThePreviousOwnersProbeState() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer oldOwner = mock(MessageListenerContainer.class);
        MessageListenerContainer newOwner = mock(MessageListenerContainer.class);
        AtomicReference<MessageListenerContainer> currentOwner = new AtomicReference<>(oldOwner);
        AtomicBoolean oldOwnerAssigned = new AtomicBoolean(true);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenAnswer(invocation -> currentOwner.get());
        when(oldOwner.isRunning()).thenReturn(true);
        when(oldOwner.getAssignedPartitions()).thenAnswer(invocation ->
                oldOwnerAssigned.get() ? Set.of(PARTITION) : Set.of());
        when(oldOwner.isPartitionPaused(PARTITION)).thenReturn(true);
        when(newOwner.isRunning()).thenReturn(true);
        when(newOwner.getAssignedPartitions()).thenReturn(Set.of(PARTITION));
        when(newOwner.isPartitionPaused(PARTITION)).thenReturn(true);
        AtomicInteger probeCount = new AtomicInteger();
        MutableClock clock = new MutableClock();
        ContentSearchKafkaAutoResumeCoordinator coordinator = coordinator(
                registry, Dependency.ELASTICSEARCH, clock,
                (probe, timeout) -> {
                    if (probeCount.incrementAndGet() == 1) {
                        // Simulate a rebalance after collection but before the old owner's result is applied.
                        oldOwnerAssigned.set(false);
                        currentOwner.set(newOwner);
                    }
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
        coordinator.probePausedPartitions(); // ownership changes while A's Probe is in flight

        assertThat(probeCount).hasValue(1);
        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        verify(oldOwner, never()).resumePartition(PARTITION);
        verify(newOwner, never()).resumePartition(PARTITION);

        clock.advanceMillis(10);
        coordinator.probePausedPartitions(); // B is rebound; A's in-flight success cannot count or Resume

        assertThat(coordinator.trackedPauseCount()).isEqualTo(1);
        assertThat(probeCount).hasValue(1);
        verify(oldOwner, never()).resumePartition(PARTITION);
        verify(newOwner, never()).resumePartition(PARTITION);
        verify(container, never()).resumePartition(PARTITION);

		clock.advanceMillis(10);
		coordinator.probePausedPartitions(); // B의 Pause 관측

		clock.advanceMillis(10);
		coordinator.probePausedPartitions(); // B 복구 Probe 진행

		// 이 시점까지는 Resume되지 않아야 함
		assertThat(probeCount).hasValue(3);
		verify(oldOwner, never()).resumePartition(PARTITION);
		verify(newOwner, never()).resumePartition(PARTITION);

		// B의 세 번째 성공으로 Resume
		coordinator.probePausedPartitions();

		assertThat(probeCount).hasValue(4);
		verify(oldOwner, never()).resumePartition(PARTITION);
		verify(newOwner).resumePartition(PARTITION);
        assertThat(probeCount).hasValue(4);
        coordinator.shutdown();
    }

    @Test
    void queuedProbeTimeoutReleasesReservationWithoutClearingTheNextProbeReservation() throws Exception {
        ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker invoker =
                new ContentSearchKafkaAutoResumeCoordinator.BoundedProbeInvoker(1, 2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch blockingProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingProbe = new CountDownLatch(1);
        CountDownLatch nextProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseNextProbe = new CountDownLatch(1);
        AtomicInteger queuedCalls = new AtomicInteger();
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicInteger duplicateCalls = new AtomicInteger();
        ContentSearchDependencyHealthProbe blockingProbe = blockingProbe(
                Dependency.ELASTICSEARCH, blockingProbeStarted, releaseBlockingProbe, new AtomicInteger()
        );
        ContentSearchDependencyHealthProbe queuedProbe = countingProbe(Dependency.MYSQL, queuedCalls);
        ContentSearchDependencyHealthProbe nextProbe = blockingProbe(
                Dependency.MYSQL, nextProbeStarted, releaseNextProbe, nextCalls
        );
        ContentSearchDependencyHealthProbe duplicateProbe = countingProbe(Dependency.MYSQL, duplicateCalls);

        try {
            Future<Boolean> blockingCheck = callers.submit(() -> invoker.check(blockingProbe, 3000));
            assertThat(blockingProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(invoker.check(queuedProbe, 20)).isFalse(); // cancelled while still queued
            assertThat(queuedCalls).hasValue(0);
            Future<Boolean> nextCheck = callers.submit(() -> invoker.check(nextProbe, 3000));
            releaseBlockingProbe.countDown();

            assertThat(nextProbeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(invoker.check(duplicateProbe, 20)).isFalse();
            assertThat(duplicateCalls).hasValue(0);

            releaseNextProbe.countDown();
            assertThat(blockingCheck.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(nextCheck.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedCalls).hasValue(0);
            assertThat(nextCalls).hasValue(1);
        } finally {
            releaseBlockingProbe.countDown();
            releaseNextProbe.countDown();
            callers.shutdownNow();
            invoker.close();
        }
    }

    private MessageListenerContainer pausedOwner(Set<TopicPartition> assigned) {
        MessageListenerContainer owner = mock(MessageListenerContainer.class);
        when(owner.isRunning()).thenReturn(true);
        when(owner.getAssignedPartitions()).thenReturn(assigned);
        when(owner.isPartitionPauseRequested(PARTITION)).thenReturn(true);
        when(owner.isPartitionPaused(PARTITION)).thenReturn(true);
        return owner;
    }

    private ContentSearchDependencyHealthProbe countingProbe(Dependency dependency, AtomicInteger calls) {
        return new ContentSearchDependencyHealthProbe() {
            @Override
            public Dependency dependency() {
                return dependency;
            }

            @Override
            public void checkHealthy() {
                calls.incrementAndGet();
            }
        };
    }

    private ContentSearchDependencyHealthProbe blockingProbe(
            Dependency dependency,
            CountDownLatch started,
            CountDownLatch release,
            AtomicInteger calls
    ) {
        return new ContentSearchDependencyHealthProbe() {
            @Override
            public Dependency dependency() {
                return dependency;
            }

            @Override
            public void checkHealthy() {
                calls.incrementAndGet();
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Simulate an I/O client that does not stop immediately on timeout cancellation.
                    }
                }
            }
        };
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
