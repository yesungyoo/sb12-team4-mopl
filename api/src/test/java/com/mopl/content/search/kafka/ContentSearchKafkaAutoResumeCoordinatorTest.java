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
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition())).thenReturn(owner);
        when(owner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> paused.get());
        doAnswer(invocation -> {
            paused.set(false);
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

    @Test
    void pauseMarkerOnFormerOwnerRemainsRecoverableAfterPartitionMovesAwayAndBack() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = pausedAssignedContainer(registry);
        MessageListenerContainer formerOwner = pausedOwner(Set.of(PARTITION));
        MessageListenerContainer currentOwner = pausedOwner(Set.of(PARTITION));
        AtomicReference<MessageListenerContainer> assignedOwner = new AtomicReference<>(formerOwner);
        AtomicBoolean formerOwnerPaused = new AtomicBoolean(true);
        when(container.getContainerFor(PARTITION.topic(), PARTITION.partition()))
                .thenAnswer(invocation -> assignedOwner.get());
        when(formerOwner.isPartitionPaused(PARTITION)).thenAnswer(invocation -> formerOwnerPaused.get());
        when(currentOwner.isPartitionPaused(PARTITION)).thenReturn(false);
        when(currentOwner.isPartitionPauseRequested(PARTITION)).thenReturn(false);
        doAnswer(invocation -> {
            formerOwnerPaused.set(false);
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
